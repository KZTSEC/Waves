package com.wavesplatform.it.sync.smartcontract.smartasset

import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.sync.smartcontract.{cryptoContextScript, pureContextScript, wavesContextScript}
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.util._
import com.wavesplatform.matcher.model.LimitOrder
import com.wavesplatform.state._
import com.wavesplatform.transaction.DataTransaction
import com.wavesplatform.transaction.assets.exchange._
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.utils.NTP
import org.scalatest.CancelAfterFailure
import play.api.libs.json.JsObject
import scorex.crypto.encode.Base64

class ExchangeSmartAssetsSuite extends BaseTransactionSuite with CancelAfterFailure {
  private val acc0 = pkByAddress(firstAddress)
  private val acc1 = pkByAddress(secondAddress)
  private val acc2 = pkByAddress(thirdAddress)

  private var dtx: DataTransaction = _

  private val sc1 = Some("true")

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    val entry1 = IntegerDataEntry("int", 24)
    val entry2 = BooleanDataEntry("bool", value = true)
    val entry3 = BinaryDataEntry("blob", ByteStr(Base64.decode("YWxpY2U=")))
    val entry4 = StringDataEntry("str", "test")

    dtx = DataTransaction.selfSigned(1, acc0, List(entry1, entry2, entry3, entry4), 0.001.waves, NTP.correctedTime()).explicitGet()
    sender.signedBroadcast(dtx.json(), waitForTx = true)
  }

  test("require using a certain matcher with smart accounts") {
    /*
    combination of smart accounts and smart assets
     */
    val script = Some(ScriptCompiler(s"""
                                       |match tx {
                                       |case s : SetAssetScriptTransaction => true
                                       |case e: ExchangeTransaction => e.sender == addressFromPublicKey(base58'${ByteStr(acc2.publicKey).base58}')
                                       |case _ => false}""".stripMargin).explicitGet()._1.bytes.value.base64)

    val smartAsset = sender
      .issue(firstAddress, "SmartAsset", "TestCoin", someAssetAmount, 0, reissuable = false, issueFee, 2, script, true)
      .id

    val smartPair = AssetPair(ByteStr.decodeBase58(smartAsset).toOption, None)

    for ((contr1, contr2, mcontr) <- Seq(
           (sc1, sc1, sc1),
           (None, sc1, None)
         )) {

      setContracts((contr1, acc0), (contr2, acc1), (mcontr, acc2))

      sender.signedBroadcast(exchangeTx(smartPair), waitForTx = true)
    }

    val scriptUpdated = Some(ScriptCompiler(s"""
                                          |match tx {
                                          |case s : SetAssetScriptTransaction => true
                                          |case e: ExchangeTransaction => e.sender == addressFromPublicKey(base58'${ByteStr(acc1.publicKey).base58}')
                                          |case _ => false}""".stripMargin).explicitGet()._1.bytes.value.base64)

    sender.setAssetScript(smartAsset, firstAddress, setAssetScriptFee, scriptUpdated, waitForTx = true)

    assertBadRequestAndMessage(sender.signedBroadcast(exchangeTx(smartPair)), errNotAllowedByToken)

    setContracts((None, acc0), (None, acc1), (None, acc2))
  }

  test("AssetPair from smart assets") {
    val assetA = sender
      .issue(firstAddress, "assetA", "TestCoin", someAssetAmount, 0, reissuable = false, issueFee, 2, Some(scriptBase64), waitForTx = true)
      .id

    val assetB = sender
      .issue(secondAddress, "assetB", "TestCoin", someAssetAmount, 0, reissuable = false, issueFee, 2, Some(scriptBase64), waitForTx = true)
      .id

    sender.transfer(secondAddress, firstAddress, 1000, minFee + smartExtraFee, Some(assetB), waitForTx = true)
    sender.transfer(firstAddress, secondAddress, 1000, minFee + smartExtraFee, Some(assetA), waitForTx = true)

    val script = Some(ScriptCompiler(s"""
                                        |let assetA = base58'$assetA'
                                        |let assetB = base58'$assetB'
                                        |match tx {
                                        |case s : SetAssetScriptTransaction => true
                                        |case e: ExchangeTransaction => (e.sellOrder.assetPair.priceAsset == assetA || e.sellOrder.assetPair.amountAsset == assetA) && (e.sellOrder.assetPair.priceAsset == assetB || e.sellOrder.assetPair.amountAsset == assetB)
                                        |case _ => false}""".stripMargin).explicitGet()._1.bytes.value.base64)

    sender.setAssetScript(assetA, firstAddress, setAssetScriptFee, script, waitForTx = true)
    sender.setAssetScript(assetB, secondAddress, setAssetScriptFee, script, waitForTx = true)

    val smartAssetPair = AssetPair(
      amountAsset = Some(ByteStr.decodeBase58(assetA).get),
      priceAsset = Some(ByteStr.decodeBase58(assetB).get)
    )

    sender.signedBroadcast(exchangeTx(smartAssetPair), waitForTx = true)

    withClue("check fee for smart accounts and smart AssetPair - extx.fee == 0.015.waves") {
      setContracts((sc1, acc0), (sc1, acc1), (sc1, acc2))

      assertBadRequestAndMessage(
        sender.signedBroadcast(exchangeTx(smartAssetPair)),
        "com.wavesplatform.transaction.assets.exchange.ExchangeTransactionV2 does not exceed minimal value of 1500000"
      )

      sender.signedBroadcast(exchangeTx(smartAssetPair, someSmart = false), waitForTx = true).id
    }

    withClue("try to use incorrect assetPair") {
      val incorrectSmartAssetPair = AssetPair(
        amountAsset = Some(ByteStr.decodeBase58(assetA).get),
        priceAsset = None
      )
      assertBadRequestAndMessage(sender.signedBroadcast(exchangeTx(incorrectSmartAssetPair)), errNotAllowedByToken)
    }

    setContracts((None, acc0), (None, acc1), (None, acc2))
  }

  test("use all functions from RIDE for asset script") {
    val script1 = Some(ScriptCompiler(cryptoContextScript).explicitGet()._1.bytes.value.base64)
    val script2 = Some(ScriptCompiler(pureContextScript(dtx)).explicitGet()._1.bytes.value.base64)
    val script3 = Some(ScriptCompiler(wavesContextScript(dtx)).explicitGet()._1.bytes.value.base64)

    List(script1, script2, script3)
      .map { i =>
        val asset = sender
          .issue(firstAddress, "assetA", "TestCoin", someAssetAmount, 0, reissuable = false, issueFee, 2, i, waitForTx = true)
          .id

        val smartPair = AssetPair(ByteStr.decodeBase58(asset).toOption, None)

        sender.signedBroadcast(exchangeTx(smartPair), waitForTx = true)
      }
  }

  def exchangeTx(pair: AssetPair, someSmart: Boolean = true): JsObject = {
    val matcher     = acc2
    val sellPrice   = (0.50 * Order.PriceConstant).toLong
    val (buy, sell) = orders(pair, 2, someSmart)

    val amount = math.min(buy.amount, sell.amount)

    val matcherFee     = if (someSmart) 1100000L else 1500000L
    val sellMatcherFee = LimitOrder.getPartialFee(sell.matcherFee, sell.amount, amount)
    val buyMatcherFee  = LimitOrder.getPartialFee(buy.matcherFee, buy.amount, amount)

    val tx = ExchangeTransactionV2
      .create(
        matcher = matcher,
        buyOrder = buy,
        sellOrder = sell,
        amount = amount,
        price = sellPrice,
        buyMatcherFee = buyMatcherFee,
        sellMatcherFee = sellMatcherFee,
        fee = matcherFee,
        timestamp = NTP.correctedTime()
      )
      .explicitGet()
      .json()

    tx
  }

  def orders(pair: AssetPair, version: Byte = 2, isSmart: Boolean = true): (Order, Order) = {
    val buyer               = acc1
    val seller              = acc0
    val matcher             = acc2
    val time                = NTP.correctedTime()
    val expirationTimestamp = time + Order.MaxLiveTime
    val buyPrice            = 1 * Order.PriceConstant
    val sellPrice           = (0.50 * Order.PriceConstant).toLong
    val mf                  = if (isSmart) 1500000L else 700000L
    val buyAmount           = 2
    val sellAmount          = 3

    val buy  = Order.buy(buyer, matcher, pair, buyAmount, buyPrice, time, expirationTimestamp, mf, version)
    val sell = Order.sell(seller, matcher, pair, sellAmount, sellPrice, time, expirationTimestamp, mf, version)

    (buy, sell)
  }

}

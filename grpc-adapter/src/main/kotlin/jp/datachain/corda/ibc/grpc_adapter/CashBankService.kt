package jp.datachain.corda.ibc.grpc_adapter

import com.google.protobuf.Empty
import ibc.lightclients.corda.v1.CashBankProto
import ibc.lightclients.corda.v1.CashBankServiceGrpc
import io.grpc.stub.StreamObserver
import jp.datachain.corda.ibc.conversion.into
import jp.datachain.corda.ibc.flows.ics20cash.IbcCashBankCreateFlow
import jp.datachain.corda.ibc.ics20cash.CashBank
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateRef
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.flows.CashIssueAndPaymentFlow
import java.util.*

class CashBankService(host: String, port: Int, username: String, password: String, private val baseId: StateRef): CashBankServiceGrpc.CashBankServiceImplBase(), CordaRPCOpsReady by CordaRPCOpsReady.create(host, port, username, password) {
    override fun createCashBank(request: CashBankProto.CreateCashBankRequest, responseObserver: StreamObserver<Empty>) {
        ops.startFlow(::IbcCashBankCreateFlow, baseId, request.bank.into()).returnValue.get()
        responseObserver.onNext(Empty.getDefaultInstance())
        responseObserver.onCompleted()
    }

    private fun vaultQueryCashBank() = ops.vaultQueryBy<CashBank>(
            QueryCriteria.LinearStateQueryCriteria(
                    externalId = listOf(baseId.toString())
            )
    )

    override fun allocateCash(request: CashBankProto.AllocateCashRequest, responseObserver: StreamObserver<Empty>) {
        val notary = vaultQueryCashBank().statesMetadata.single().notary as Party
        val flowRequest = CashIssueAndPaymentFlow.IssueAndPaymentRequest(
                amount = Amount(
                        request.amount.toLong(),
                        Currency.getInstance(request.currency)
                ),
                issueRef = OpaqueBytes(ByteArray(1)),
                recipient = request.owner.into(),
                notary = notary,
                anonymous = false)
        ops.startFlow(::CashIssueAndPaymentFlow, flowRequest).returnValue.get()
        responseObserver.onNext(Empty.getDefaultInstance())
        responseObserver.onCompleted()
    }

    override fun queryCashBank(request: Empty, responseObserver: StreamObserver<CashBankProto.CashBank>) {
        val reply: CashBankProto.CashBank = vaultQueryCashBank().states.single().state.data.into()
        responseObserver.onNext(reply)
        responseObserver.onCompleted()
    }
}
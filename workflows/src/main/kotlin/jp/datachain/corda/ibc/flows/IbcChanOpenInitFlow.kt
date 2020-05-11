package jp.datachain.corda.ibc.flows

import co.paralleluniverse.fibers.Suspendable
import jp.datachain.corda.ibc.contracts.Ibc
import jp.datachain.corda.ibc.ics24.Host
import jp.datachain.corda.ibc.ics24.Identifier
import jp.datachain.corda.ibc.ics25.Handler.chanOpenInit
import jp.datachain.corda.ibc.ics4.ChannelOrder
import jp.datachain.corda.ibc.states.Connection
import jp.datachain.corda.ibc.types.Version
import net.corda.core.contracts.ReferencedStateAndRef
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

object IbcChanOpenInitFlow {
    @StartableByRPC
    @InitiatingFlow
    class Initiator(
            val hostIdentifier: Identifier,
            val order: ChannelOrder,
            val connectionHops: List<Identifier>,
            val counterpartyPortIdentifier: Identifier,
            val counterpartyChannelIdentifier: Identifier,
            val version: Version.Single
    ) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call() : SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val builder = TransactionBuilder(notary)

            // query host from vault
            val host = serviceHub.vaultService.queryBy<Host>(
                    QueryCriteria.LinearStateQueryCriteria(linearId = listOf(hostIdentifier.toUniqueIdentifier()))
            ).states.single()
            val participants = host.state.data.participants.map{it as Party}
            require(participants.contains(ourIdentity))

            // query connection from vault
            val connId = connectionHops.single()
            val conn = serviceHub.vaultService.queryBy<Connection>(
                    QueryCriteria.LinearStateQueryCriteria(linearId = listOf(connId.toUniqueIdentifier()))
            ).states.single()

            // calculate a newly created channel state and an updated host state
            val portId = host.state.data.generateIdentifier()
            val chanId = host.state.data.generateIdentifier()
            val (newHost, newChan) = Pair(host.state.data, conn.state.data).chanOpenInit(
                    order,
                    connectionHops,
                    portId,
                    chanId,
                    counterpartyPortIdentifier,
                    counterpartyChannelIdentifier,
                    version)

            // build tx
            builder.addCommand(Ibc.Commands.ChanOpenInit(
                    order,
                    connectionHops,
                    portId,
                    chanId,
                    counterpartyPortIdentifier,
                    counterpartyChannelIdentifier,
                    version
            ), ourIdentity.owningKey)
                    .addInputState(host)
                    .addReferenceState(ReferencedStateAndRef(conn))
                    .addOutputState(newHost)
                    .addOutputState(newChan)
            val tx = serviceHub.signInitialTransaction(builder)

            val sessions = (participants - ourIdentity).map{initiateFlow(it)}
            val stx = subFlow(FinalityFlow(tx, sessions))
            return stx
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val counterPartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val stx = subFlow(ReceiveFinalityFlow(counterPartySession))
            println(stx)
        }
    }
}
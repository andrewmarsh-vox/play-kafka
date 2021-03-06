package example.wallet

/*
Wallet debit / credit example, using a join
Preferred option, as it enforces order of aggregate first
 */

import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.kstream.Joined
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.state.KeyValueStore
import polaris.kafka.PolarisKafka

fun main(args : Array<String>) {

    // The debit processor turns payment requests into a debit transaction
    //
    with(PolarisKafka("debit-processor", "localhost:9092", "http://localhost:8081")) {
        val paymentsInflight = topic<TransactionKey, Payment>("payments-inflight")
        val transactions = topic<TransactionKey, Transaction>("transactions")

        consumeStream(paymentsInflight)
                .map { key, payment ->
                    KeyValue(key, Transaction(payment.getAmount(),
                            payment.getToAccount(),
                            payment.getReference(),
                            payment.getDescription(),
                            "DEBIT"))
                }
                .through(transactions.topic, transactions.producedWith())

        start()
    }

    // The credit processor turns debit transactions into credit transactions
    // Crediting the recipient if sufficient balance
    // Crediting the original debit account if insufficient balance
    //
    with(PolarisKafka("credit-processor", "localhost:9092", "http://localhost:8081")) {

        val transactions = topic<TransactionKey, Transaction>("transactions")
        val transactionsStream = consumeStream(transactions)

        // Build a materialized view of transactions to calculate balance
        //
        val balancesTable = transactionsStream
                .groupByKey()
                .aggregate({ 0 }, { key, transaction: Transaction, balance : Int ->

                    val newBalance : Int
                    if (transaction.getType() == "CREDIT") {
                        newBalance = balance + transaction.getAmount()
                    }
                    else {
                        newBalance = balance - transaction.getAmount()
                    }
                    println("Materialized account [${key.getFromAccount()}], new balance [$newBalance]")
                    newBalance
                }, Materialized.`as`<TransactionKey, Int, KeyValueStore<Bytes, ByteArray>>("BalanceKeyValueStore")
                        .withCachingDisabled() // Materialize on every message
                        .withKeySerde(transactions.keySerde)
                        .withValueSerde(Serdes.Integer()))

        // Join the transaction stream with current balance
        //
        transactionsStream
                .filter { _, transaction -> transaction.getType() == "DEBIT" }
                .join(balancesTable, { debitTransaction, balanceAfterDebit ->

                    val creditTransaction = debitTransaction
                    creditTransaction.setType("CREDIT")

                    if (balanceAfterDebit != null && balanceAfterDebit >= 0) {
                        // Credit the recipient account
                        println("Crediting [${debitTransaction.getAccountToCredit()}]...")
                        KeyValue(TransactionKey(debitTransaction.getAccountToCredit()), creditTransaction)
                    }
                    else {
                        // Insufficient funds, reverse (CREDIT same account)
                        println("Insufficient funds, reversing...")
                        KeyValue(null, debitTransaction)
                    }
                }, Joined.with(transactions.keySerde, transactions.valueSerde, Serdes.Integer()))
                .map { key, creditTransaction ->
                    if (creditTransaction.key == null) {
                        // Reversal, rekey to original debit account
                        KeyValue(key, creditTransaction.value)
                    }
                    else {
                        KeyValue(creditTransaction.key, creditTransaction.value)
                    }
                }
                .to(transactions.topic, transactions.producedWith())

        start()
    }
}
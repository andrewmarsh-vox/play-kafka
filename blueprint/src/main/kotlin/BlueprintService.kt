import java.util.Collections
import java.util.Properties

import com.landoop.telecom.telecomitalia.telecommunications.smsCallInternet

import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler
import org.apache.kafka.streams.kstream.Consumed

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde

class BlueprintService {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {

            val stringSerde = Serdes.serdeFrom(String::class.java)
            val serdeConfig = Collections.singletonMap(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081")
            val italiaSerde = SpecificAvroSerde<smsCallInternet>()
            italiaSerde.configure(serdeConfig, false)

            val builder = StreamsBuilder()
            builder.stream("telecom_italia_data", Consumed.with(stringSerde, italiaSerde))
                    .foreach({ key, message -> println(message) })

            val streams = KafkaStreams(builder.build(), getKafkaProperties())
            streams.start()
        }


        fun getKafkaProperties(
                kafka_server: String = "localhost:9092",
                kafka_app: String = "blueprint",
                kafka_stream_threads: Int = 1
        ): Properties {
            val properties = Properties()

            properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafka_server)
            properties.put(StreamsConfig.APPLICATION_ID_CONFIG, kafka_app)
            properties.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, kafka_stream_threads)
            properties.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndFailExceptionHandler::class.java)

            return properties
        }
    }
}
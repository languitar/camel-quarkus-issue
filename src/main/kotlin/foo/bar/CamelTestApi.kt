package foo.bar

import org.apache.camel.CamelContext
import org.apache.camel.FluentProducerTemplate
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.jackson.ListJacksonDataFormat
import org.apache.camel.model.SagaPropagation
import org.apache.camel.saga.InMemorySagaService
import org.apache.camel.service.lra.LRASagaService
import java.time.Duration
import java.util.*
import javax.enterprise.context.ApplicationScoped
import javax.inject.Inject
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.core.Response

@Path("/trigger")
class CamelTestApi {

    @Inject
    lateinit var producer: FluentProducerTemplate

    @GET
    @Consumes("application/json")
    fun get(): Response {
        println("test route called")
        val res = producer.to("direct:test-saga").send()
        return when (res.isFailed) {
            true -> Response.serverError()
            false -> Response.ok(res.message.body)
        }.build()
    }
}

@ApplicationScoped
class TestSaga : RouteBuilder {
    constructor() : super()
    constructor(context: CamelContext?) : super(context)

    override fun configure() {
        context.addService(LRASagaService())

        from("direct:test-saga")
            .saga()
            .timeout(Duration.ofMinutes(1))
            .to("direct:test-saga-step-1")
            .to("direct:test-saga-step-2")
            .process { it.message.body = UUID.randomUUID() }
            .log("Saga succeeded")
            .end()

        from("direct:test-saga-step-1")
            .saga()
            .propagation(SagaPropagation.MANDATORY)
            .compensation("direct:test-saga-step-1-compensation")
            .log("Saga step 1 started")
            .to("http://www.randomnumberapi.com/api/v1.0/random?min=0&max=1&count=1")
            .log("Received \${body}")
            .unmarshal(ListJacksonDataFormat(Int::class.java))
            .process { it.message.body = (it.message.body as List<*>)[0] }
            .log("Next request shall fail: \${body}")

        from("direct:test-saga-step-1-compensation")
            .log("Compensating step 1")

        from("direct:test-saga-step-2")
            .saga()
            .propagation(SagaPropagation.MANDATORY)
            .log("Saga step 2 called")
            .process {
                it.message.body = when (it.message.body as Int) {
                    1 -> "-broken"
                    else -> ""
                }
            }
            .toD("http://www.randomnumberapi.com\${body}/api/v1.0/random?min=0&max=1&count=10")
            .unmarshal(ListJacksonDataFormat(Int::class.java))
    }
}

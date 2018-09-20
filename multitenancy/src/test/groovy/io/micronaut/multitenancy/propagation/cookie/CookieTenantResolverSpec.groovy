package io.micronaut.multitenancy.propagation.cookie

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import io.micronaut.core.io.socket.SocketUtils
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.cookie.Cookie
import io.micronaut.multitenancy.propagation.TenantPropagationHttpClientFilter
import io.micronaut.multitenancy.tenantresolver.CookieTenantResolver
import io.micronaut.multitenancy.tenantresolver.HttpHeaderTenantResolver
import io.micronaut.multitenancy.tenantresolver.TenantResolver
import io.micronaut.multitenancy.writer.CookieTenantWriter
import io.micronaut.multitenancy.writer.HttpHeaderTenantWriter
import io.micronaut.multitenancy.writer.TenantWriter
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

@Stepwise
class CookieTenantResolverSpec extends Specification {

    static final SPEC_NAME_PROPERTY = 'spec.name'

    @Shared
    int gormPort

    @AutoCleanup
    @Shared
    EmbeddedServer gormEmbeddedServer

    @AutoCleanup
    @Shared
    RxHttpClient gormClient

    @AutoCleanup
    @Shared
    EmbeddedServer gatewayEmbeddedServer

    @AutoCleanup
    @Shared
    RxHttpClient gatewayClient

    def setupSpec() {
        gormPort = SocketUtils.findAvailableTcpPort()
    }

    def "setup gorm server"() {
        given:
        Map gormConfig = [
                'micronaut.server.port'                       : gormPort,
                (SPEC_NAME_PROPERTY)                          : 'multitenancy.cookie.gorm',
                'micronaut.multitenancy.tenantresolver.cookie.enabled': true
        ]

        gormEmbeddedServer = ApplicationContext.run(EmbeddedServer, gormConfig, Environment.TEST)

        gormClient = gormEmbeddedServer.applicationContext.createBean(RxHttpClient, gormEmbeddedServer.getURL())

        when:
        for (Class beanClazz : [BookService, BooksController, Bootstrap]) {
            gormEmbeddedServer.applicationContext.getBean(beanClazz)
        }

        then:
        noExceptionThrown()
    }

    def "setup gateway server"() {
        given:
        Map gatewayConfig = [
                (SPEC_NAME_PROPERTY): 'multitenancy.cookie.gateway',
                'micronaut.http.services.books.url': "http://localhost:${gormPort}",
                'micronaut.multitenancy.propagation.enabled': true,
                'micronaut.multitenancy.propagation.service-id-regex': 'books',
                'micronaut.multitenancy.tenantwriter.cookie.enabled': true,
                'micronaut.multitenancy.tenantresolver.cookie.enabled': true
        ]

        gatewayEmbeddedServer = ApplicationContext.run(EmbeddedServer, gatewayConfig, Environment.TEST)

        when:
        for (Class beanClazz : [
                GatewayController,
                BooksClient,
                CookieTenantWriter,
                CookieTenantResolver,
                TenantWriter,
                TenantResolver,
                TenantPropagationHttpClientFilter
        ]) {
            gatewayEmbeddedServer.applicationContext.getBean(beanClazz)
        }

        then:
        noExceptionThrown()

        when:
        gatewayClient = gatewayEmbeddedServer.applicationContext.createBean(RxHttpClient, gatewayEmbeddedServer.getURL())

        then:
        noExceptionThrown()
    }

    def "fetch books for watson and sherlock directly from the books microservice, the tenantId is in the HTTP Header. They get only their books"() {
        when:
        HttpResponse rsp = gormClient.toBlocking().exchange(HttpRequest.GET('/api/books')
                .cookie(Cookie.of("tenantId", "sherlock")), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Sherlock diary'] == rsp.body()

        when:
        rsp = gormClient.toBlocking().exchange(HttpRequest.GET('/api/books')
                .cookie(Cookie.of("tenantId", "watson")), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Watson diary'] == rsp.body()
    }

    def "fetch books for watson and sherlock, since the tenant ID is in the HTTP header and its propagated. They get only their books"() {
        when:
        HttpResponse rsp = gatewayClient.toBlocking().exchange(HttpRequest.GET('/')
                .cookie(Cookie.of("tenantId", "sherlock")), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Sherlock diary'] == rsp.body()

        when:
        rsp = gatewayClient.toBlocking().exchange(HttpRequest.GET('/')
                .cookie(Cookie.of("tenantId", "watson")), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Watson diary'] == rsp.body()
    }
}

package net.javaguides.rpererv;

import java.util.Arrays;
import java.util.Collections;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigatewayv2.*;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * ApiStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 15/02/2026 - 10:24
 * @since 1.17
 */
public class ApiStack extends Stack {

    public ApiStack(final Construct scope, final String id, final StackProps props, NlbStack nlbStack) {
        super(scope, id, props);

        LogGroup logGroup = new LogGroup(this, "ECommerceApiLogs", LogGroupProps.builder()
                .logGroupName("ECommerceApi")
                .removalPolicy(RemovalPolicy.DESTROY)
                .retention(RetentionDays.ONE_MONTH)
                .build());

        // 1. Crear la HTTP API (v2)
        HttpApi httpApi = new HttpApi(this, "ECommerceHttpApi", HttpApiProps.builder()
                .apiName("ECommerceApi")
                .build());

        // --- AÑADE ESTO PARA LOS LOGS ---
        // Configuramos el stage por defecto para que envíe logs al LogGroup
                if (httpApi.getDefaultStage() != null) {
                    CfnStage cfnStage = (CfnStage) httpApi.getDefaultStage().getNode().getDefaultChild();
                    cfnStage.setAccessLogSettings(CfnStage.AccessLogSettingsProperty.builder()
                            .destinationArn(logGroup.getLogGroupArn())
                            .format("$context.identity.sourceIp - $context.identity.caller - $context.authorizer.principalId [$context.requestTime] \"$context.httpMethod $context.routeKey $context.protocol\" $context.status $context.responseLength $context.requestId")
                            .build());
                }
        // --------------------------------

        // 2. Añadir una ruta de prueba que use la integración de tu NlbStack
        // Esta ruta enviará el tráfico a través del VpcLink -> NLB -> ALB
        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/test")
                .methods(Collections.singletonList(HttpMethod.GET))
                .integration(nlbStack.getNlbIntegration())
                .build());

        // ESTO MOSTRARÁ LA URL EN TU CONSOLA AL FINALIZAR
        CfnOutput.Builder.create(this, "ApiEndpoint")
                .value(httpApi.getApiEndpoint() + "/test")
                .description("URL para probar el encadenamiento NLB -> ALB")
                .build();

        // GET /api/products
        // GET /api/products?code=CODE1
        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/api/products") // Coincide con tu Controller de Spring
                .methods(Collections.singletonList(HttpMethod.GET))
                .integration(nlbStack.getNlbIntegration())
                .build());

        // POST /api/products (Crear)
        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/api/products")
                .methods(Collections.singletonList(HttpMethod.POST))
                .integration(nlbStack.getNlbIntegration())
                .build());

        // Rutas con ID: GET, PUT y DELETE /api/products/{id}
        // En HTTP API v2, se usa la sintaxis {proxy+} o {id}
        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/api/products/{id}")
                .methods(Arrays.asList(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE))
                .integration(nlbStack.getNlbIntegration())
                .build());

        CfnOutput.Builder.create(this, "ProductsEndpoint")
                .value(httpApi.getApiEndpoint() + "/api/products")
                .description("URL para ver tus productos de Spring Boot")
                .build();
    }
}

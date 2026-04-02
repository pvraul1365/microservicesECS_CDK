package net.javaguides.rpererv;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AppProtocol;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.FargateTaskDefinitionProps;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.ecs.Secret;
import software.amazon.awscdk.services.ecs.ServiceConnectProps;
import software.amazon.awscdk.services.ecs.ServiceConnectService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateServiceProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.ssm.IStringParameter;
import software.amazon.awscdk.services.ssm.SecureStringParameterAttributes;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.services.ssm.StringParameterAttributes;
import software.constructs.Construct;

public class PhotoAlbumsMicroserviceStack extends Stack {

    private final ApplicationLoadBalancedFargateService albService;

    public PhotoAlbumsMicroserviceStack(final Construct scope, final String id, final StackProps props,
                                        PhotoAlbumsServiceProps serviceProps) {
        super(scope, id, props);

        // 1. Definición de la Tarea
        FargateTaskDefinition taskDefinition = new FargateTaskDefinition(this, "PhotoAlbumsTaskDef",
                FargateTaskDefinitionProps.builder()
                        .family("photo-albums-microservice-task-definition")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .runtimePlatform(RuntimePlatform.builder()
                                .cpuArchitecture(CpuArchitecture.X86_64)
                                .operatingSystemFamily(OperatingSystemFamily.LINUX)
                                .build())
                        .build());

        taskDefinition.addToExecutionRolePolicy(PolicyStatement.Builder.create()
                .actions(Arrays.asList("ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"))
                .resources(List.of("*"))
                .build());

        // Añadimos permiso para leer parámetros de SSM al Execution Role
        // 1. Permiso para entrar a la "oficina" de SSM y tomar los parámetros
        taskDefinition.getExecutionRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(Arrays.asList("ssm:GetParameters", "ssm:GetParameter"))
                .resources(List.of("arn:aws:ssm:" + this.getRegion() + ":" + this.getAccount() + ":parameter/prod/photo-albums-microservice/*"))
                .build());

        // 2. Permiso para usar la "llave" (KMS) y descifrar el valor de la contraseña
        taskDefinition.getExecutionRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of("kms:Decrypt"))
                // Si usaste la clave por defecto de AWS, puedes usar "*" o el ARN de 'alias/aws/ssm'
                .resources(List.of("*"))
                .build());

        // Recuperamos las referencias de Parameter Store (SSM)
        // Esto solo crea una referencia, no extrae el valor aún
        IStringParameter dbNameParam = StringParameter.fromStringParameterAttributes(this, "DbNameRef",
                StringParameterAttributes.builder()
                        .parameterName("/prod/photo-albums-microservice/mysql/database-name")
                        .build());
        String dbPort = StringParameter.valueForStringParameter(this, "/prod/photo-albums-microservice/mysql/database-port");
        String dbUser = StringParameter.valueForStringParameter(this, "/prod/photo-albums-microservice/mysql/database-user-name");
        // En lugar de IStringParameter, usamos la clase Secret de ECS
        Secret dbPasswordSecret = Secret.fromSsmParameter(
                StringParameter.fromSecureStringParameterAttributes(this, "SecurePassReference",
                        SecureStringParameterAttributes.builder()
                                .parameterName("/prod/photo-albums-microservice/mysql/database-user-password")
                                .version(1)
                                .build())
        );

        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("spring.profiles.active", "prod");
        envVariables.put("HOST_NAME", serviceProps.database().getDbInstanceEndpointAddress());
        envVariables.put("DATABASE_PORT", dbPort);
        envVariables.put("DATABASE_USER_NAME", dbUser);

        // Crea un mapa separado para los secretos
        Map<String, Secret> secretVariables = new HashMap<>();
        secretVariables.put("DATABASE_NAME", Secret.fromSsmParameter(dbNameParam));
        secretVariables.put("DATABASE_USER_PASSWORD", dbPasswordSecret);

        // 2. Añadir el Contenedor
        ContainerDefinition container = taskDefinition.addContainer("PhotoAlbumsContainer",
                ContainerDefinitionOptions.builder()
                        .containerName("photo-albums-microservice") // Corregido typo 'phto'
                        .image(ContainerImage.fromEcrRepository(serviceProps.repository()))
                        .environment(envVariables)
                        .secrets(secretVariables)
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logRetention(RetentionDays.ONE_DAY)
                                .streamPrefix("photo-albums-microservice")
                                .build()))
                        .build());

        // Mapeo de puerto con Alias para Service Connect
        // En el PortMapping del contenedor
        container.addPortMappings(PortMapping.builder()
                .name("photo-albums-port-mapping")
                .containerPort(8080)
                .protocol(Protocol.TCP)
                .appProtocol(AppProtocol.getHttp()) // <--- Intenta con 'getHttp()' o 'http()' según el autocompletado
                .build());

        // 3. Crear el Servicio con Service Connect Habilitado
        this.albService = new ApplicationLoadBalancedFargateService(this, "AlbumsALBService",
                ApplicationLoadBalancedFargateServiceProps.builder()
                        .cluster(serviceProps.cluster())
                        .serviceName("photo-albums-microservice-lb-service")
                        .loadBalancerName("photo-albums-microservice-lb")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .desiredCount(1)
                        .taskDefinition(taskDefinition)
                        .publicLoadBalancer(true)
                        .assignPublicIp(true)
                        .healthCheckGracePeriod(Duration.seconds(60))
                        .build());

        // 3.1. Obtener el servicio de ECS subyacente del Pattern
        FargateService fargateService = albService.getService();

        // 3.2. Configurar Service Connect manualmente en el servicio creado
        fargateService.enableServiceConnect(ServiceConnectProps.builder()
                .namespace("local")
                .services(List.of(ServiceConnectService.builder()
                        .portMappingName("photo-albums-port-mapping")
                        .dnsName("photo-albums")
                        .port(8080)
                        .build()))
                .build());

        // 4. Configurar Health Check
        albService.getTargetGroup().configureHealthCheck(
                HealthCheck.builder()
                        .path("/actuator/health")
                        .port("8080")
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.HTTP)
                        .healthyThresholdCount(2)
                        .unhealthyThresholdCount(5)
                        .interval(Duration.seconds(30))
                        .build()
        );

        // Seguridad: Permitir entrada al puerto 8080 desde cualquier lugar (como pediste)
        albService.getService().getConnections().allowFromAnyIpv4(Port.tcp(8080), "Allow public access to port 8080");

        // Seguridad: DB
        albService.getService().getConnections().allowTo(serviceProps.database(), Port.tcp(3306), "Allow Microservice to contact MySQL");

        CfnOutput.Builder.create(this, "AlbumsServiceURL")
                .value("http://" + albService.getLoadBalancer().getLoadBalancerDnsName())
                .description("URL pública para acceder al Microservicio de Photo Albums")
                .build();
    }

    public ApplicationLoadBalancedFargateService getAlbService() {
        return albService;
    }
}

record PhotoAlbumsServiceProps(Cluster cluster, Repository repository, DatabaseInstance database) {}
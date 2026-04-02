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
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.FargateTaskDefinitionProps;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.ecs.Secret;
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

/**
 * UsersMicroserviceStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 28/03/2026 - 11:22
 * @since 1.17
 */
public class UsersMicroserviceStack extends Stack {

    private final ApplicationLoadBalancedFargateService albService;

    public UsersMicroserviceStack(final Construct scope, final String id, final StackProps props,
                                  UsersServiceProps serviceProps) {
        super(scope, id, props);

        // 1. Definición de la Tarea
        FargateTaskDefinition taskDefinition = new FargateTaskDefinition(this, "UsersTaskDef",
                FargateTaskDefinitionProps.builder()
                        .family("users-microservice-task-definition")
                        .cpu(512)    // 0.5 vCPU
                        .memoryLimitMiB(1024) // 1 GB
                        .runtimePlatform(RuntimePlatform.builder()
                                .cpuArchitecture(CpuArchitecture.X86_64)
                                .operatingSystemFamily(OperatingSystemFamily.LINUX)
                                .build())
                        .build());

        // Esto asegura que la tarea tenga permiso para hacer 'Pull' de la imagen en ECR
        taskDefinition.addToExecutionRolePolicy(PolicyStatement.Builder.create()
                .actions(Arrays.asList("ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"))
                .resources(Arrays.asList("*"))
                .build());

        // Añadimos permiso para leer parámetros de SSM al Execution Role
        // 1. Permiso para entrar a la "oficina" de SSM y tomar los parámetros
        taskDefinition.getExecutionRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(Arrays.asList("ssm:GetParameters", "ssm:GetParameter"))
                .resources(List.of("arn:aws:ssm:" + this.getRegion() + ":" + this.getAccount() + ":parameter/prod/users-microservice/*"))
                .build());

        // 2. Permiso para usar la "llave" (KMS) y descifrar el valor de la contraseña
        taskDefinition.getExecutionRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of("kms:Decrypt"))
                // Si usaste la clave por defecto de AWS, puedes usar "*" o el ARN de 'alias/aws/ssm'
                .resources(List.of("*"))
                .build());

        // Recuperamos las referencias de Parameter Store (SSM)
        IStringParameter dbNameParam = StringParameter.fromStringParameterAttributes(this, "UsersDbNameRef",
                StringParameterAttributes.builder()
                        .parameterName("/prod/users-microservice/mysql/database-name")
                        .build());
        String dbPort = StringParameter.valueForStringParameter(this, "/prod/users-microservice/mysql/database-port");
        String dbUser = StringParameter.valueForStringParameter(this, "/prod/users-microservice/mysql/database-user-name");
        // Nota: Para SecureString, usamos valueForSecureStringParameter
        // Para la contraseña (SecureString)
        // En lugar de IStringParameter, usamos la clase Secret de ECS
        Secret dbPasswordSecret = Secret.fromSsmParameter(
                StringParameter.fromSecureStringParameterAttributes(this, "SecurePassReference",
                        SecureStringParameterAttributes.builder()
                                .parameterName("/prod/users-microservice/mysql/database-user-password")
                                .version(1)
                                .build())
        );

        // DB.1. Preparar el mapa de variables de entorno
        Map<String, String> envVariables = new HashMap<>();

        // Variables estándar de Spring
        envVariables.put("spring.profiles.active", "prod");

        // Variables que coinciden con tu application.properties
        // El endpoint de RDS (ej: users.xxxx.us-east-1.rds.amazonaws.com)
        envVariables.put("HOST_NAME", serviceProps.database().getDbInstanceEndpointAddress());
        envVariables.put("DATABASE_PORT", dbPort);
        envVariables.put("DATABASE_USER_NAME", dbUser); // El master username que definiste
        envVariables.put("ALBUMS_URL", "http://photo-albums:8080/albums"); // URL del microservicio de albums usando Service Connect (nombre del servicio + puerto)

        // Crea un mapa separado para los secretos
        Map<String, Secret> secretVariables = new HashMap<>();
        secretVariables.put("DATABASE_NAME", Secret.fromSsmParameter(dbNameParam));
        secretVariables.put("DATABASE_USER_PASSWORD", dbPasswordSecret);

        // 2. Añadir el Contenedor (Puerto 8081 para Spring Boot)
        ContainerDefinition container = taskDefinition.addContainer("UsersContainer",
                ContainerDefinitionOptions.builder()
                        .containerName("users-microservice")
                        .image(ContainerImage.fromEcrRepository(serviceProps.repository()))
                        .environment(envVariables)
                        .secrets(secretVariables)
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logRetention(RetentionDays.ONE_DAY)
                                .streamPrefix("users-microservice")
                                .build()))
                        .build());

        container.addPortMappings(PortMapping.builder()
                .containerPort(8081)
                .protocol(Protocol.TCP)
                .build());

        // 3. Crear el Servicio Balanceado (El "Pattern")
        this.albService = new ApplicationLoadBalancedFargateService(this, "UsersALBService",
                ApplicationLoadBalancedFargateServiceProps.builder()
                        .cluster(serviceProps.cluster())           // Tu cluster existente
                        .serviceName("users-microservice-lb-service")
                        .loadBalancerName("users-microservice-lb") // Nombre del ALB
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .desiredCount(1)                           // ¡1 Instancias levantadas!
                        .taskDefinition(taskDefinition)
                        .publicLoadBalancer(true)                  // Acceso desde internet
                        .assignPublicIp(true)                      // Necesario al no tener NAT Gateway
                        .healthCheckGracePeriod(Duration.seconds(60)) // Le da 1 minuto a Spring para arrancar antes de empezar a juzgar su salud
                        .build());

        // 4. Configurar el Health Check (Actuator)
        this.albService.getTargetGroup().configureHealthCheck(
                HealthCheck.builder()
                        .path("/actuator/health")
                        .port("8081")
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.HTTP)
                        .healthyThresholdCount(2)
                        .unhealthyThresholdCount(5)
                        .interval(Duration.seconds(30))
                        .build()
        );

        // DB.2. Dar permiso al Microservicio para conectarse a la DB
        // serviceProps.database().getConnections().allowDefaultPortFrom(albService.getService(), "Allow ECS to connect to MySQL");
        this.albService.getService().getConnections().allowTo(serviceProps.database(), Port.tcp(3306), "Allow Microservice to contact MySQL");

        /*
        // 5. Configurar el Auto Scaling
        // Primero definimos el rango de capacidad (Min: 1, Max: 3)
        ScalableTaskCount scalableTarget = albService.getService().autoScaleTaskCount(
                EnableScalingProps.builder()
                        .minCapacity(1)
                        .maxCapacity(3)
                        .build()
        );

        // Aplicamos la política de Target Tracking basada en el número de peticiones al ALB
        scalableTarget.scaleOnRequestCount("UsersMicroserviceRequestScaling",
                RequestCountScalingProps.builder()
                        .policyName("users-micro-service-target-scaling-policy")
                        .requestsPerTarget(10)            // Tu valor objetivo: 10 peticiones
                        .scaleOutCooldown(Duration.seconds(30)) // Tiempo de espera para subir
                        .scaleInCooldown(Duration.seconds(30))  // Tiempo de espera para bajar
                        .targetGroup(albService.getTargetGroup())
                        .build()
        );

        // 6. Añadir política adicional por utilización de CPU
        scalableTarget.scaleOnCpuUtilization("UsersMicroserviceCpuScaling",
                CpuUtilizationScalingProps.builder()
                        .policyName("users-microservice-cpu-scaling-policy")
                        .targetUtilizationPercent(70) // Objetivo: Mantener la CPU al 70%
                        .scaleOutCooldown(Duration.seconds(30))
                        .scaleInCooldown(Duration.seconds(30))
                        .build()
        );
         */

        CfnOutput.Builder.create(this, "UsersServiceURL")
                .value("http://" + albService.getLoadBalancer().getLoadBalancerDnsName())
                .description("URL pública para acceder al Microservicio de Usuarios")
                .exportName("UsersServiceExternalURL")
                .build();
    }

    public ApplicationLoadBalancedFargateService getAlbService() {
        return albService;
    }
}

record UsersServiceProps(Cluster cluster, Repository repository, DatabaseInstance database) {}
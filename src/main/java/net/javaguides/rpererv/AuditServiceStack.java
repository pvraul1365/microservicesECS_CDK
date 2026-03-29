package net.javaguides.rpererv;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.amazon.awscdk.services.sqs.QueueProps;
import software.constructs.Construct;

/**
 * AuditServiceStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 21/03/2026 - 19:12
 * @since 1.17
 */
public class AuditServiceStack extends Stack {

    public AuditServiceStack(final Construct scope, final String id,
                             final StackProps props, AuditServiceProps auditServiceProps) {
        super(scope, id, props);

        Queue productEventsDlq = new Queue(this, "ProductEventsDlq",
                QueueProps.builder()
                        .queueName("product-events-dlq")
                        .retentionPeriod(Duration.days(10))
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .build()
                );

        Queue productEventsQueue = new Queue(this, "ProductEventsQueue",
                QueueProps.builder()
                        .queueName("product-events")
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .deadLetterQueue(DeadLetterQueue.builder()
                                .queue(productEventsDlq)
                                .maxReceiveCount(3) // to set the maximum number of times a message can be received before being sent to the dead-letter queue, which is important for handling failed messages and avoiding infinite processing loops
                                .build())
                        .build()
        );
        auditServiceProps.productEventsTopic().addSubscription(new SqsSubscription(productEventsQueue)); // to subscribe the SQS queue to the SNS topic, which will allow us to receive messages from the topic in the queue and process them in the Fargate service

        // 1. Fargate Task Definition
        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("audit-service") // name of the task definition, same as the family name in the ECS service
                        .cpu(512) // 0.5 vCPU
                        .memoryLimitMiB(1024) // 1 GB RAM
                        // --- ESTO ES LO NUEVO ---
                        .runtimePlatform(RuntimePlatform.builder()
                                .cpuArchitecture(CpuArchitecture.ARM64) // <--- Idioma nativo M4
                                .operatingSystemFamily(OperatingSystemFamily.LINUX)
                                .build())
                        // ------------------------
                        .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess")); // to grant permissions to the Fargate task role to write traces to AWS X-Ray, which is necessary for the application to be able to send traces to AWS X-Ray
        productEventsQueue.grantConsumeMessages(fargateTaskDefinition.getTaskRole());

        // 2. Log Driver for Container Definition
        AwsLogDriver logDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "logGroup",
                        LogGroupProps.builder()
                                .logGroupName("AuditService") // name of the log group, which will be used in CloudWatch Logs
                                .removalPolicy(RemovalPolicy.DESTROY) // to delete the log group when the stack is deleted, for cleanup purposes
                                .retention(RetentionDays.ONE_MONTH)
                                .build()
                ))
                .streamPrefix("AuditService") // prefix for the log stream, which will be used in CloudWatch Logs
                .build());

        // 3. Adding a container to the Fargate Task Definition (using the ECR repository created in the RepositoryStack)
        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("SERVER_PORT", "9090"); // environment variable for the application inside the container, which will be used to configure the port where the application listens
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000"); // to enable AWS X-Ray tracing for the application, which will allow us to monitor and troubleshoot the application in production, and to visualize the traces in the AWS X-Ray console
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
        envVariables.put("AWS_XRAY_TRACING_NAME", "auditservice"); // to set the name of the service in the AWS X-Ray console, which will help us to identify the traces from this
        envVariables.put("AWS_SQS_QUEUE_PRODUCT_EVENTS_URL", productEventsQueue.getQueueUrl());
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO"); // to set the logging level for the application, which will be used in the application to configure the logging behavior (e.g., to log only INFO level messages and above)

        fargateTaskDefinition.addContainer("AuditServiceContainer", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromEcrRepository(auditServiceProps.repository(), "1.1.0")) // to use the ECR repository created in the RepositoryStack
                .containerName("auditService")
                .logging(logDriver) // to use the log driver created above for logging
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(9090) // port where the application inside the container listens, which will be used in the target group of the ALB
                        .protocol(Protocol.TCP)
                        .build()))
                .environment(envVariables)
                .cpu(384)
                .memoryLimitMiB(896)
                .build());

        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest")) // to use the AWS X-Ray daemon container image from the public ECR registry, which will allow us to collect and send traces from the application to AWS X-Ray
                .containerName("XRayAuditService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup", LogGroupProps.builder()
                                .logGroupName("XRayAuditService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_MONTH)
                                .build()))
                        .streamPrefix("XRayAuditService")
                        .build())) // to use the same log driver for the X-Ray daemon container
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000) // port where the X-Ray daemon listens for incoming traces
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());

        // 4. Create the Fargate Service and associate it with the ALB created in the NlbStack, using private subnets for security
        FargateService fargateService = FargateService.Builder.create(this, "AuditService")
                .serviceName("AuditService") // name of the ECS service, same as the service
                .cluster(auditServiceProps.cluster())
                .taskDefinition(fargateTaskDefinition)
                .desiredCount(2)
                .assignPublicIp(false) // to not assign public IPs to the Fargate tasks, which is recommended for security reasons, since the ALB will be in charge of routing the traffic to the Fargate service and we don't want to expose the tasks directly to the internet
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS) // <--- Seguridad con salida a internet
                        .build())
                .build();
        auditServiceProps.repository().grantPull(fargateTaskDefinition.getExecutionRole());
        fargateService.getConnections().allowFrom(auditServiceProps.nlbStack().getApplicationLoadBalancer(), Port.tcp(9090)); // to allow incoming traffic from the ALB to the Fargate service on the port where the application listens (9090)

        // 4. Associate the Fargate Service with the ALB created in the NlbStack, using an Application Target Group with health checks and a short deregistration delay for faster recovery in case of failures
        ApplicationTargetGroup targetGroup = auditServiceProps.nlbStack().getApplicationListener()
                .addTargets("AuditServiceAlbTarget",
                        AddApplicationTargetsProps.builder()
                                .targetGroupName("auditServiceAlb")
                                .port(9090)
                                .protocol(ApplicationProtocol.HTTP)
                                .targets(Collections.singletonList(fargateService))
                                .deregistrationDelay(Duration.seconds(30)) // to reduce the time it takes for the ALB to stop routing traffic to unhealthy instances, which is important for faster recovery in case of failures
                                .healthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder()
                                        .enabled(true)
                                        .interval(Duration.seconds(30)) // to set the interval between health checks, which is important for faster detection of unhealthy instances
                                        .timeout(Duration.seconds(10)) // to set the timeout for health checks, which is important for faster detection of unhealthy instances
                                        .path("/actuator/health") // to set the path for health checks, which should be an endpoint in your application that returns a 200 status code when the application is healthy, and a 500 status code when the application is unhealthy
                                        //.healthyHttpCodes("200") // to set the HTTP status codes that indicate a healthy instance, which should match the response from your health check endpoint
                                        .port("9090") // to set the port for health checks, which should match the container port where your application listens
                                        .build())
                                .build());

    }
}

record AuditServiceProps(
        Vpc vpc,
        Cluster cluster,
        NlbStack nlbStack,
        Repository repository,
        Topic productEventsTopic
) {
}

package net.javaguides.rpererv;

import java.util.HashMap;
import java.util.Map;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class MicroservicesEcsCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        // Es buena práctica usar variables de entorno o valores quemados si el entorno es fijo
        final Environment environment = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build();

        // Metadata para organizar costos y equipo en AWS
        Map<String, String> infraestructureTags = new HashMap<>();
        infraestructureTags.put("team", "RperezvCode");
        infraestructureTags.put("cost", "MicroservicesInfraestructure");

        // Objeto base de propiedades para todos los Stacks
        StackProps commonProps = StackProps.builder()
                .env(environment)
                .tags(infraestructureTags)
                .build();

        // 1. Repositorios ECR
        EcrStack ecrStack = new EcrStack(app, "MicroservicesEcr", commonProps);

        // 2. Red (VPC) - La base de todo
        VpcStack vpcStack = new VpcStack(app, "MicroservicesVpc", commonProps);

        // 3. BASE DE DATOS
        // Le pasamos la VPC porque la DB necesita saber dónde ubicarse
        DatabaseStack databaseStack = new DatabaseStack(app, "MicroservicesDatabase",
                commonProps, vpcStack.getVpc());

        // 4. Cluster ECS
        ClusterStack clusterStack = new ClusterStack(app, "MicroservicesCluster",
                commonProps,
                new ClusterStackProps(vpcStack.getVpc()));

        // 5. Servicio Fargate
        // Nota: El paso de ecrStack.getUsersMicroserviceRepository() crea una dependencia implícita
        new UsersMicroserviceStack(app, "UsersMicroserviceService",
                commonProps,
                new UsersServiceProps(
                        clusterStack.getCluster(),
                        ecrStack.getUsersMicroserviceRepository(),
                        databaseStack.getDatabase()
                ));

        // Generar el template de CloudFormation
        app.synth();
    }
}
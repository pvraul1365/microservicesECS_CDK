package net.javaguides.rpererv;

import java.util.HashMap;
import java.util.Map;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Port;

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
        UsersDatabaseStack usersDatabaseStack = new UsersDatabaseStack(app, "UsersDatabase",
                commonProps, vpcStack.getVpc());
        AlbumsDatabaseStack albumsDbStack = new AlbumsDatabaseStack(app, "AlbumsDatabase",
                commonProps, vpcStack.getVpc());

        // 4. Cluster ECS
        ClusterStack clusterStack = new ClusterStack(app, "MicroservicesCluster",
                commonProps,
                new ClusterStackProps(vpcStack.getVpc()));



        // 5.1 Servicio Fargate photo-albums
        // Nota: El paso de ecrStack.getUsersMicroserviceRepository() crea una dependencia implícita
        PhotoAlbumsMicroserviceStack albumsStack = new PhotoAlbumsMicroserviceStack(app, "PhotoAlbumsMicroserviceService",
                commonProps,
                new PhotoAlbumsServiceProps(
                        clusterStack.getCluster(),
                        ecrStack.getPhotoAlbumsMicroserviceRepository(),
                        albumsDbStack.getDatabase()
                ));

        // 5.2 Servicio Fargate users
        // Nota: El paso de ecrStack.getUsersMicroserviceRepository() crea una dependencia implícita
        UsersMicroserviceStack usersStack = new UsersMicroserviceStack(app, "UsersMicroserviceService",
                commonProps,
                new UsersServiceProps(
                        clusterStack.getCluster(),
                        ecrStack.getUsersMicroserviceRepository(),
                        usersDatabaseStack.getDatabase()
                ));

        // Permitir que el Security Group de Users se conecte al de Albums en el puerto 8080
        // Añadimos .getService() antes de .getConnections()
        // En lugar de allowFrom (que Albums mire a Users),
        // usamos allowTo (que Users pida permiso para salir hacia Albums)
        usersStack.getAlbService().getService().getConnections().allowTo(
                albumsStack.getAlbService().getService(),
                Port.tcp(8080),
                "Users microservice can send traffic to Albums"
        );

        usersStack.getAlbService().getService().enableServiceConnect(
                software.amazon.awscdk.services.ecs.ServiceConnectProps.builder()
                        .namespace("local") // IMPORTANTE: El mismo que definiste en el Cluster y en Albums
                        .build()
        );
        //usersStack.addDependency(albumsStack);

        // Generar el template de CloudFormation
        app.synth();
    }
}
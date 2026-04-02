package net.javaguides.rpererv;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

public class ParametersStack extends Stack {

    public ParametersStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Parámetros para el microservicio de PHOTO-ALBUMS
        createStringParam("/prod/photo-albums-microservice/mysql/database-name", "albums");
        createStringParam("/prod/photo-albums-microservice/mysql/database-port", "3306");
        createStringParam("/prod/photo-albums-microservice/mysql/database-user-name", "admin");
        
        // NOTA: Para SecureString (Contraseña)
        // CloudFormation/CDK no permite poner el valor de un SecureString directamente 
        // por consola de comandos para evitar que quede en el historial. 
        // Se suele crear como String normal y luego se cambia a Secure manualmente, 
        // o se usa esta forma para "reservar" el nombre:
        StringParameter.Builder.create(this, "AlbumsDbPassword")
                .parameterName("/prod/photo-albums-microservice/mysql/database-user-password")
                .stringValue("fysgeS-ruzfik-2tyrhu") // Valor inicial
                .build();

        // Puedes repetir lo mismo para el microservicio de USERS si lo deseas
        // Parámetros para el microservicio de USERS
        createStringParam("/prod/users-microservice/mysql/database-name", "users");
        createStringParam("/prod/users-microservice/mysql/database-port", "3306");
        createStringParam("/prod/users-microservice/mysql/database-user-name", "admin");
        StringParameter.Builder.create(this, "UsersDbPassword")
                .parameterName("/prod/users-microservice/mysql/database-user-password")
                .stringValue("zugqIn-tuzxot-juxki0") // Valor inicial
                .build();
    }

    private void createStringParam(String name, String value) {
        StringParameter.Builder.create(this, name.replace("/", "-"))
                .parameterName(name)
                .stringValue(value)
                .build();
    }
}
package net.javaguides.rpererv;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.RepositoryProps;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.constructs.Construct;

/**
 * EcrStack
 * <p>
 * Created by IntelliJ, Spring Framework Guru.
 *
 * @author architecture - pvraul
 * @version 14/02/2026 - 09:25
 * @since 1.17
 */
public class EcrStack extends Stack {

    private final Repository usersMicroserviceRepository;
    private final Repository photoAlbumsMicroserviceRepository;

    public EcrStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.usersMicroserviceRepository = new Repository(this, "UsersMicroservice", RepositoryProps.builder()
                .repositoryName("users-microservice") // name of the repository, same as the container name in the task definition
                .removalPolicy(RemovalPolicy.DESTROY) // to automatically delete the repository when the stack is deleted
                .imageTagMutability(TagMutability.MUTABLE) // to prevent overwriting existing images with the same tag
                .emptyOnDelete(true) // to automatically delete images when the repository is deleted
                .build());

        this.photoAlbumsMicroserviceRepository = new Repository(this, "PhotoAlbumsMicroservices", RepositoryProps.builder()
                .repositoryName("photo-albums-microservices") // name of the repository, same as the container name in the task definition
                .removalPolicy(RemovalPolicy.DESTROY) // to automatically delete the repository when the stack is deleted
                .imageTagMutability(TagMutability.MUTABLE) // to prevent overwriting existing images with the same tag
                .emptyOnDelete(true) // to automatically delete images when the repository is deleted
                .build());
    }

    public Repository getUsersMicroserviceRepository() {
        return usersMicroserviceRepository;
    }

    public Repository getPhotoAlbumsMicroserviceRepository() {
        return photoAlbumsMicroserviceRepository;
    }
}

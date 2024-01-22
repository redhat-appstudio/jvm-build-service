
    create sequence AdditionalDownload_SEQ start with 1 increment by 50;

    create sequence ArtifactIdentifier_SEQ start with 1 increment by 50;

    create sequence ArtifactLabelName_SEQ start with 1 increment by 50;

    create sequence BuildAttempt_SEQ start with 1 increment by 50;

    create sequence BuildFile_SEQ start with 1 increment by 50;

    create sequence BuildIdentifier_SEQ start with 1 increment by 50;

    create sequence BuildQueue_SEQ start with 1 increment by 50;

    create sequence BuildSBOMDiscoveryInfo_SEQ start with 1 increment by 50;

    create sequence ContainerImage_SEQ start with 1 increment by 50;

    create sequence DependencySet_SEQ start with 1 increment by 50;

    create sequence GithubActionsBuild_SEQ start with 1 increment by 50;

    create sequence IdentifiedDependency_SEQ start with 1 increment by 50;

    create sequence jbs_user_SEQ start with 1 increment by 50;

    create sequence MavenArtifact_SEQ start with 1 increment by 50;

    create sequence MavenArtifactLabel_SEQ start with 1 increment by 50;

    create sequence Role_SEQ start with 1 increment by 50;

    create sequence ScmRepository_SEQ start with 1 increment by 50;

    create sequence ShadingDetails_SEQ start with 1 increment by 50;

    create sequence StoredArtifactBuild_SEQ start with 1 increment by 50;

    create sequence StoredDependencyBuild_SEQ start with 1 increment by 50;

    create sequence VersionDiscoveryQueue_SEQ start with 1 increment by 50;

    create table AdditionalDownload (
        id bigint not null,
        binaryPath varchar(255),
        fileName varchar(255),
        fileType varchar(255),
        packageName varchar(255),
        sha256 varchar(255),
        uri text,
        buildAttempt_id bigint not null,
        primary key (id)
    );

    create table ArtifactIdentifier (
        id bigint not null,
        maven_artifact text,
        maven_group text,
        primary key (id)
    );

    create table ArtifactLabelName (
        id bigint not null,
        name varchar(255) unique,
        primary key (id)
    );

    create table BuildAttempt (
        id bigint not null,
        additionalMemory integer not null,
        allowedDifferences text,
        antVersion varchar(255),
        buildId varchar(255),
        buildLogsUrl varchar(255),
        buildPipelineUrl varchar(255),
        builderImage text,
        commandLine text,
        disableSubModules boolean not null,
        enforceVersion varchar(255),
        gitArchiveSha varchar(255),
        gitArchiveTag text,
        gitArchiveUrl text,
        gradleVersion varchar(255),
        hermeticBuilderImage text,
        jdk varchar(255),
        mavenRepository varchar(255),
        mavenVersion varchar(255),
        outputImage text,
        outputImageDigest varchar(255),
        passedVerification boolean not null,
        postBuildScript text,
        preBuildImage text,
        preBuildScript text,
        repositories varchar(255),
        sbtVersion varchar(255),
        startTime timestamp(6),
        successful boolean not null,
        tool varchar(255),
        upstreamDifferences text,
        dependencyBuild_id bigint not null,
        primary key (id)
    );

    create table BuildAttempt_AdditionalDownload (
        BuildAttempt_id bigint not null,
        additionalDownloads_id bigint not null unique
    );

    create table BuildAttempt_BuildFile (
        BuildAttempt_id bigint not null,
        storedBuildResults_id bigint not null unique
    );

    create table BuildFile (
        id bigint not null,
        type smallint check (type between 0 and 3),
        uri varchar(255) not null,
        build_id bigint not null,
        primary key (id)
    );

    create table BuildIdentifier (
        id bigint not null,
        contextPath varchar(255) not null,
        dependencyBuildName varchar(255) not null unique,
        hash varchar(255) not null,
        tag varchar(255) not null,
        repository_id bigint not null,
        primary key (id),
        constraint UKr9fj0ukvygb9vxnsre30v9u5i unique (repository_id, tag, hash, contextPath)
    );

    create table BuildQueue (
        id bigint not null,
        priority boolean not null,
        rebuild boolean not null,
        mavenArtifact_id bigint,
        primary key (id)
    );

    create table BuildSBOMDiscoveryInfo (
        id bigint not null,
        discoveredGavs text,
        succeeded boolean not null,
        build_id bigint not null unique,
        primary key (id)
    );

    create table ContainerImage (
        id bigint not null,
        analysisComplete boolean not null,
        analysisFailed boolean not null,
        digest varchar(255) not null,
        image text not null,
        tag varchar(255),
        dependencySet_id bigint unique,
        primary key (id),
        constraint UKi0aayfne2l6hnguhj1c4wm273 unique (image, digest)
    );

    create table DependencySet (
        id bigint not null,
        identifier varchar(255) not null,
        type varchar(255) not null,
        primary key (id),
        constraint UKp108vrp0295l9r85o2lxgbl72 unique (identifier, type)
    );

    create table GithubActionsBuild (
        id bigint not null,
        commit varchar(255),
        complete boolean not null,
        creationTime timestamp(6) with time zone not null,
        prUrl varchar(255),
        repository varchar(255),
        workflowRunId bigint not null,
        dependencySet_id bigint unique,
        primary key (id)
    );

    create table IdentifiedDependency (
        id bigint not null,
        attributes varchar(255),
        buildComplete boolean not null,
        buildId varchar(255),
        buildSuccessful boolean not null,
        source varchar(255),
        dependencySet_id bigint,
        mavenArtifact_id bigint,
        primary key (id)
    );

    create table jbs_user (
        id bigint not null,
        pass varchar(255),
        username varchar(255),
        primary key (id)
    );

    create table jbs_user_Role (
        users_id bigint not null,
        roles_id bigint not null
    );

    create table MavenArtifact (
        id bigint not null,
        version varchar(255) not null,
        identifier_id bigint,
        primary key (id),
        constraint mavengavunique unique (version, identifier_id)
    );

    create table MavenArtifactLabel (
        id bigint not null,
        artifact_id bigint not null,
        name_id bigint not null,
        primary key (id),
        constraint UK8kqx7s0ork1vsrm0sn715tc9o unique (name_id, artifact_id)
    );

    create table Role (
        id bigint not null,
        role varchar(255),
        primary key (id)
    );

    create table ScmRepository (
        id bigint not null,
        url varchar(255) unique,
        primary key (id)
    );

    create table ShadingDetails (
        id bigint not null,
        allowed boolean not null,
        buildId varchar(255),
        rebuildAvailable boolean not null,
        source varchar(255),
        contaminant_id bigint not null,
        primary key (id)
    );

    create table ShadingDetails_MavenArtifact (
        ShadingDetails_id bigint not null,
        contaminatedArtifacts_id bigint not null
    );

    create table StoredArtifactBuild (
        id bigint not null,
        creationTimestamp timestamp(6) with time zone not null,
        message text,
        name varchar(255) not null,
        state varchar(255) not null,
        uid varchar(255) not null,
        buildIdentifier_id bigint,
        mavenArtifact_id bigint not null,
        primary key (id),
        constraint UKmyiuk6w44fa3wc33hygo6newe unique (mavenArtifact_id, uid)
    );

    create table StoredDependencyBuild (
        id bigint not null,
        buildDiscoveryUrl varchar(255),
        buildYamlUrl varchar(255),
        contaminated boolean not null,
        creationTimestamp timestamp(6) with time zone not null,
        succeeded boolean not null,
        uid varchar(255) not null,
        version varchar(255),
        buildIdentifier_id bigint not null,
        primary key (id),
        constraint UK3vhc9gqgiq7hppr3iglc2n0b8 unique (buildIdentifier_id)
    );

    create table StoredDependencyBuild_BuildAttempt (
        StoredDependencyBuild_id bigint not null,
        buildAttempts_id bigint not null unique
    );

    create table StoredDependencyBuild_MavenArtifact (
        StoredDependencyBuild_id bigint not null,
        producedArtifacts_id bigint not null
    );

    create table StoredDependencyBuild_ShadingDetails (
        StoredDependencyBuild_id bigint not null,
        shadingDetails_id bigint not null unique
    );

    create table VersionDiscoveryQueue (
        id bigint not null,
        lastRun timestamp(6) with time zone,
        artifactIdentifier_id bigint not null unique,
        primary key (id)
    );

    alter table if exists AdditionalDownload
       add constraint FK39eh5a8xxp46q3gtmeyncch1h
       foreign key (buildAttempt_id)
       references BuildAttempt;

    alter table if exists BuildAttempt
       add constraint FK2vqtsy25ccjmikislqo3a0aim
       foreign key (dependencyBuild_id)
       references StoredDependencyBuild;

    alter table if exists BuildAttempt_AdditionalDownload
       add constraint FKbiicbxkn2vh9dllrjma0odjw0
       foreign key (additionalDownloads_id)
       references AdditionalDownload;

    alter table if exists BuildAttempt_AdditionalDownload
       add constraint FKbmavpy9fpr3g86o5qfkmf5srb
       foreign key (BuildAttempt_id)
       references BuildAttempt;

    alter table if exists BuildAttempt_BuildFile
       add constraint FKaeo4m7letkmwfyoyopm8yj0gj
       foreign key (storedBuildResults_id)
       references BuildFile;

    alter table if exists BuildAttempt_BuildFile
       add constraint FKc5ag6faehmls1owi8oykcs9qy
       foreign key (BuildAttempt_id)
       references BuildAttempt;

    alter table if exists BuildFile
       add constraint FKmke5rkkm1roj9nq6c77yyr42j
       foreign key (build_id)
       references BuildAttempt;

    alter table if exists BuildIdentifier
       add constraint FKhy8g6bovqm0fnesgul4293uv0
       foreign key (repository_id)
       references ScmRepository;

    alter table if exists BuildQueue
       add constraint FKdul89emvqjn1ne0jidcyqs6s1
       foreign key (mavenArtifact_id)
       references MavenArtifact;

    alter table if exists BuildSBOMDiscoveryInfo
       add constraint FKxguvu08ig0ktv7b0tnjr38dx
       foreign key (build_id)
       references StoredDependencyBuild;

    alter table if exists ContainerImage
       add constraint FKegmav1y7dg5t2ykl7bdn7sk3e
       foreign key (dependencySet_id)
       references DependencySet;

    alter table if exists GithubActionsBuild
       add constraint FK4b0h7wssxcjduxyntp07yonj
       foreign key (dependencySet_id)
       references DependencySet;

    alter table if exists IdentifiedDependency
       add constraint FK3pnb3jtp9ckrvq5i7toiaqjh0
       foreign key (dependencySet_id)
       references DependencySet;

    alter table if exists IdentifiedDependency
       add constraint FKi7f1ero4w4os5o1b96ut7tncb
       foreign key (mavenArtifact_id)
       references MavenArtifact;

    alter table if exists jbs_user_Role
       add constraint FKgm8053kwsm0bf8diecjsrm5wh
       foreign key (roles_id)
       references Role;

    alter table if exists jbs_user_Role
       add constraint FKeojvfxx1yoge641w29ncfc5ih
       foreign key (users_id)
       references jbs_user;

    alter table if exists MavenArtifact
       add constraint FKl9mu28j56nnr9dgmlelntn1rw
       foreign key (identifier_id)
       references ArtifactIdentifier;

    alter table if exists MavenArtifactLabel
       add constraint FK655fjw29db296xsnwaa4cux02
       foreign key (artifact_id)
       references MavenArtifact;

    alter table if exists MavenArtifactLabel
       add constraint FK8dxbi5yqum5yaldfr2qa8a63q
       foreign key (name_id)
       references ArtifactLabelName;

    alter table if exists ShadingDetails
       add constraint FKijuafi86lbamrk4rx9v9ih36n
       foreign key (contaminant_id)
       references MavenArtifact;

    alter table if exists ShadingDetails_MavenArtifact
       add constraint FKcsmwpetci1vywfi82qewxi1g2
       foreign key (contaminatedArtifacts_id)
       references MavenArtifact;

    alter table if exists ShadingDetails_MavenArtifact
       add constraint FKtebabkifoet26ei0xilant7l
       foreign key (ShadingDetails_id)
       references ShadingDetails;

    alter table if exists StoredArtifactBuild
       add constraint FK6pda7fm0eqj60yjy4v2bu7163
       foreign key (buildIdentifier_id)
       references BuildIdentifier;

    alter table if exists StoredArtifactBuild
       add constraint FKdc6wbe97oyhmpjww6ukg1u0q7
       foreign key (mavenArtifact_id)
       references MavenArtifact;

    alter table if exists StoredDependencyBuild
       add constraint FK7hob7xntbx8beddme3twb0mxm
       foreign key (buildIdentifier_id)
       references BuildIdentifier;

    alter table if exists StoredDependencyBuild_BuildAttempt
       add constraint FKmwoxb55k088vga72oev550d1
       foreign key (buildAttempts_id)
       references BuildAttempt;

    alter table if exists StoredDependencyBuild_BuildAttempt
       add constraint FKgfodrlq1erw0qn5p39axeb0fq
       foreign key (StoredDependencyBuild_id)
       references StoredDependencyBuild;

    alter table if exists StoredDependencyBuild_MavenArtifact
       add constraint FKhche325otxfgggxd5a7u5hdtf
       foreign key (producedArtifacts_id)
       references MavenArtifact;

    alter table if exists StoredDependencyBuild_MavenArtifact
       add constraint FKrqvdnenqvtw15sprk88fx1rnc
       foreign key (StoredDependencyBuild_id)
       references StoredDependencyBuild;

    alter table if exists StoredDependencyBuild_ShadingDetails
       add constraint FKsec76urqowvrcboayagv2dy2c
       foreign key (shadingDetails_id)
       references ShadingDetails;

    alter table if exists StoredDependencyBuild_ShadingDetails
       add constraint FKngenliybt8mfng3smkeugxvlf
       foreign key (StoredDependencyBuild_id)
       references StoredDependencyBuild;

    alter table if exists VersionDiscoveryQueue
       add constraint FKhfmeop7e32qo1um0phetom2ut
       foreign key (artifactIdentifier_id)
       references ArtifactIdentifier;

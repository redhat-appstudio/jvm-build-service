/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type BuildAttemptDTO = {
    id: number;
    buildId: string;
    jdk?: string;
    mavenVersion?: string;
    gradleVersion?: string;
    sbtVersion?: string;
    antVersion?: string;
    tool?: string;
    builderImage?: string;
    preBuildImage?: string;
    hermeticBuilderImage?: string;
    outputImage?: string;
    outputImageDigest?: string;
    commandLine?: string;
    preBuildScript?: string;
    postBuildScript?: string;
    enforceVersion?: string;
    disableSubModules?: boolean;
    additionalMemory?: number;
    repositories?: string;
    allowedDifferences?: string;
    buildLogsUrl?: string;
    buildPipelineUrl?: string;
    mavenRepository?: string;
    successful?: boolean;
    passedVerification?: boolean;
    upstreamDifferences: Record<string, Array<string>>;
    gitArchiveSha?: string;
    gitArchiveTag?: string;
    gitArchiveUrl?: string;
    diagnosticDockerFile?: string;
};


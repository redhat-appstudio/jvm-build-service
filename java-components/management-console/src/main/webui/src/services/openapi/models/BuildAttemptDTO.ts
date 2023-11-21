/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */

export type BuildAttemptDTO = {
    id: number;
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
    successful?: boolean;
    passedVerification?: boolean;
    upstreamDifferences?: string;
};


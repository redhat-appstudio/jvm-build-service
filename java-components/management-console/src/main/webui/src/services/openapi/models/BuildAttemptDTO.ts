/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { Instant } from './Instant';
import type { ShadingDetails } from './ShadingDetails';
export type BuildAttemptDTO = {
    id: number;
    buildId: string;
    label: string;
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
    contaminated?: boolean;
    upstreamDifferences: Record<string, Array<string>>;
    gitArchiveSha?: string;
    gitArchiveTag?: string;
    gitArchiveUrl?: string;
    diagnosticDockerFile?: string;
    startTime?: Instant;
    shadingDetails?: Array<ShadingDetails>;
    artifacts?: Array<string>;
};


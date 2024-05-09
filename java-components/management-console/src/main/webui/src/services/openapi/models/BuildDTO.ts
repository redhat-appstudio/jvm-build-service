/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ArtifactListDTO } from './ArtifactListDTO';
import type { BuildAttemptDTO } from './BuildAttemptDTO';
export type BuildDTO = {
    id: number;
    name: string;
    scmRepo: string;
    tag: string;
    commit: string;
    contextPath?: string;
    succeeded: boolean;
    contaminated: boolean;
    verified: boolean;
    successfulBuild?: BuildAttemptDTO;
    buildAttempts: Array<BuildAttemptDTO>;
    inQueue: boolean;
    buildSbomDependencySetId: number;
    artifactList: Array<ArtifactListDTO>;
};


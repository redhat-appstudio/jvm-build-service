/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BuildAttemptDTO } from './BuildAttemptDTO';
import type { ShadingDetails } from './ShadingDetails';
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
    artifacts?: Array<string>;
    successfulBuild?: BuildAttemptDTO;
    buildAttempts?: Array<BuildAttemptDTO>;
    shadingDetails?: Array<ShadingDetails>;
    inQueue: boolean;
    buildSbomDependencySetId: number;
};


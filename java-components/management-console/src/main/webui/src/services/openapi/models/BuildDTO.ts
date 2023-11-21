/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */

import type { BuildAttemptDTO } from './BuildAttemptDTO';

export type BuildDTO = {
    id: number;
    name: string;
    scmRepo?: string;
    tag?: string;
    commit?: string;
    contextPath?: string;
    succeeded?: boolean;
    contaminated?: boolean;
    artifacts?: Array<string>;
    successfulBuild?: BuildAttemptDTO;
    buildAttempts?: Array<BuildAttemptDTO>;
    inQueue?: boolean;
};


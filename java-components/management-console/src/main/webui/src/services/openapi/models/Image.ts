/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */

import type { Dependency } from './Dependency';

export type Image = {
    string: string;
    analysisComplete: boolean;
    dependencies?: Array<Dependency>;
    totalDependencies: number;
    untrustedDependencies: number;
};


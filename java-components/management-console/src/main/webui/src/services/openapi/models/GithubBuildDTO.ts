/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */

import type { IdentifiedDependencyDTO } from './IdentifiedDependencyDTO';

export type GithubBuildDTO = {
    name: string;
    buildsComponent: boolean;
    dependencies?: Array<IdentifiedDependencyDTO>;
    totalDependencies: number;
    untrustedDependencies: number;
    trustedDependencies: number;
    availableBuilds: number;
};


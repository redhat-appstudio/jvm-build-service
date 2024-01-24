/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */

export type ArtifactDTO = {
    id: number;
    name: string;
    gav: string;
    scmRepo: string;
    tag: string;
    commit: string;
    contextPath?: string;
    dependencyBuildName?: string;
    dependencyBuildId?: number;
    succeeded?: boolean;
    missing?: boolean;
    message?: string;
};


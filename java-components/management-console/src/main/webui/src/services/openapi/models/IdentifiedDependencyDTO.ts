/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type IdentifiedDependencyDTO = {
    gav: string;
    source: string;
    dependencyBuildIdentifier?: string;
    buildAttemptId?: string;
    shadedInto?: string;
    inQueue: boolean;
    buildSuccess: boolean;
    attributes: Record<string, string>;
};


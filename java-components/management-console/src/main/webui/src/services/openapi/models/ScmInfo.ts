/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RepositoryInfo } from './RepositoryInfo';
import type { TagMapping } from './TagMapping';
export type ScmInfo = {
    type?: string;
    uri?: string;
    path?: string;
    private?: boolean;
    tagMapping?: Array<TagMapping>;
    uriWithoutFragment?: string;
    legacyRepos?: Array<RepositoryInfo>;
};


/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AdditionalDownload } from './AdditionalDownload';
export type BuildRecipeInfo = {
    enforceVersion?: boolean;
    additionalArgs?: Array<string>;
    alternativeArgs?: Array<string>;
    repositories?: Array<string>;
    toolVersion?: string;
    javaVersion?: string;
    preBuildScript?: string;
    postBuildScript?: string;
    disableSubmodules?: boolean;
    additionalMemory?: number;
    additionalDownloads?: Array<AdditionalDownload>;
    disabledPlugins?: Array<string>;
    runTests?: boolean;
    additionalBuilds?: Record<string, BuildRecipeInfo>;
    allowedDifferences?: Array<string>;
    tool?: string;
};


/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { MavenArtifact } from './MavenArtifact';
export type ShadingDetails = {
    id?: number;
    contaminant?: MavenArtifact;
    contaminatedArtifacts?: Array<MavenArtifact>;
    buildId?: string;
    source?: string;
    allowed?: boolean;
    rebuildAvailable?: boolean;
};


/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ArtifactLabelName } from '../models/ArtifactLabelName';
import type { MavenArtifactLabel } from '../models/MavenArtifactLabel';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ArtifactLabelResourceService {
    /**
     * @returns ArtifactLabelName OK
     * @throws ApiError
     */
    public static getApiArtifactLabels(): CancelablePromise<Array<ArtifactLabelName>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifact-labels',
        });
    }
    /**
     * @param name
     * @param search
     * @returns MavenArtifactLabel OK
     * @throws ApiError
     */
    public static getApiArtifactLabelsValues(
        name?: string,
        search?: string,
    ): CancelablePromise<Array<MavenArtifactLabel>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifact-labels/values',
            query: {
                'name': name,
                'search': search,
            },
        });
    }
}

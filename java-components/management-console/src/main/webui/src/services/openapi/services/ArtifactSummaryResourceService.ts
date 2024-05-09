/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ArtifactSummaryDTO } from '../models/ArtifactSummaryDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ArtifactSummaryResourceService {
    /**
     * @param label
     * @returns ArtifactSummaryDTO OK
     * @throws ApiError
     */
    public static getApiArtifactSummary(
        label?: string,
    ): CancelablePromise<ArtifactSummaryDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifact/summary',
            query: {
                'label': label,
            },
        });
    }
}

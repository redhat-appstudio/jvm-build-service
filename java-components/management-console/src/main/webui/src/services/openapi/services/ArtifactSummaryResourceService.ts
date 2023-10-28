/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ArtifactSummaryDTO } from '../models/ArtifactSummaryDTO';

import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';

export class ArtifactSummaryResourceService {

    /**
     * @returns ArtifactSummaryDTO OK
     * @throws ApiError
     */
    public static getApiArtifactSummary(): CancelablePromise<ArtifactSummaryDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifact/summary',
        });
    }

}

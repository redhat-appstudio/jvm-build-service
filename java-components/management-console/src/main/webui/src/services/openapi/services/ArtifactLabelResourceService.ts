/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ArtifactLabelName } from '../models/ArtifactLabelName';

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

}

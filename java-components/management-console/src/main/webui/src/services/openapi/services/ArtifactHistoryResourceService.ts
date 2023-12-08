/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { PageParametersArtifactListDTO } from '../models/PageParametersArtifactListDTO';

import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';

export class ArtifactHistoryResourceService {

    /**
     * @param page
     * @param perPage
     * @returns PageParametersArtifactListDTO OK
     * @throws ApiError
     */
    public static getApiArtifactsHistory(
        page?: number,
        perPage?: number,
    ): CancelablePromise<PageParametersArtifactListDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifacts/history',
            query: {
                'page': page,
                'perPage': perPage,
            },
        });
    }

}

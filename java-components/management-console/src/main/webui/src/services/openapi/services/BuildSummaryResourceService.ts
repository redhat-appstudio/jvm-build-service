/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BuildSummaryDTO } from '../models/BuildSummaryDTO';

import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';

export class BuildSummaryResourceService {

    /**
     * @param page
     * @param perPage
     * @returns BuildSummaryDTO OK
     * @throws ApiError
     */
    public static getApiBuildsStatus(
        page?: number,
        perPage?: number,
    ): CancelablePromise<BuildSummaryDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/status',
            query: {
                'page': page,
                'perPage': perPage,
            },
        });
    }

}

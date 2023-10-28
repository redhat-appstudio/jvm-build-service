/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BuildDTO } from '../models/BuildDTO';
import type { PageParametersBuildListDTO } from '../models/PageParametersBuildListDTO';

import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';

export class BuildHistoryResourceService {

    /**
     * @param page
     * @param perPage
     * @returns PageParametersBuildListDTO OK
     * @throws ApiError
     */
    public static getApiBuildsHistory(
        page?: number,
        perPage?: number,
    ): CancelablePromise<PageParametersBuildListDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/history',
            query: {
                'page': page,
                'perPage': perPage,
            },
        });
    }

    /**
     * @param id
     * @returns BuildDTO OK
     * @throws ApiError
     */
    public static getBuild(
        id: number,
    ): CancelablePromise<BuildDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/history/{id}',
            path: {
                'id': id,
            },
        });
    }

}

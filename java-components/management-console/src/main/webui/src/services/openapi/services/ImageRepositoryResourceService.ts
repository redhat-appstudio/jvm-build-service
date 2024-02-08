/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { PageParametersString } from '../models/PageParametersString';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ImageRepositoryResourceService {
    /**
     * @param page
     * @param perPage
     * @returns PageParametersString OK
     * @throws ApiError
     */
    public static getApiImageRepository(
        page?: number,
        perPage?: number,
    ): CancelablePromise<PageParametersString> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/image-repository',
            query: {
                'page': page,
                'perPage': perPage,
            },
        });
    }
}

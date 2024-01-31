/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { PageParametersImageDTO } from '../models/PageParametersImageDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ImageResourceService {
    /**
     * @param page
     * @param perPage
     * @returns PageParametersImageDTO OK
     * @throws ApiError
     */
    public static getApiImage(
        page?: number,
        perPage?: number,
    ): CancelablePromise<PageParametersImageDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/image',
            query: {
                'page': page,
                'perPage': perPage,
            },
        });
    }
    /**
     * @param requestBody
     * @returns void
     * @throws ApiError
     */
    public static putApiImage(
        requestBody?: string,
    ): CancelablePromise<void> {
        return __request(OpenAPI, {
            method: 'PUT',
            url: '/api/image',
            body: requestBody,
            mediaType: 'text/plain',
        });
    }
}

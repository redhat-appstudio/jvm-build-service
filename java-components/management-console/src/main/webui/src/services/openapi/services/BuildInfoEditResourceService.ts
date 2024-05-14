/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BuildEditInfo } from '../models/BuildEditInfo';
import type { EditResult1 } from '../models/EditResult1';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class BuildInfoEditResourceService {
    /**
     * @param gav
     * @returns BuildEditInfo OK
     * @throws ApiError
     */
    public static getApiBuildInfoEdit(
        gav?: string,
    ): CancelablePromise<BuildEditInfo> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/build-info/edit',
            query: {
                'gav': gav,
            },
        });
    }
    /**
     * @param requestBody
     * @returns EditResult1 OK
     * @throws ApiError
     */
    public static postApiBuildInfoEdit(
        requestBody?: BuildEditInfo,
    ): CancelablePromise<EditResult1> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/build-info/edit',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
}

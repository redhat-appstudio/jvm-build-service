/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { EditResult } from '../models/EditResult';
import type { ScmEditInfo } from '../models/ScmEditInfo';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ArtifactEditResourceService {
    /**
     * @param gav
     * @returns ScmEditInfo OK
     * @throws ApiError
     */
    public static getApiArtifactsEdit(
        gav?: string,
    ): CancelablePromise<ScmEditInfo> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifacts/edit',
            query: {
                'gav': gav,
            },
        });
    }
    /**
     * @param requestBody
     * @returns EditResult OK
     * @throws ApiError
     */
    public static postApiArtifactsEdit(
        requestBody?: ScmEditInfo,
    ): CancelablePromise<EditResult> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/artifacts/edit',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * @param requestBody
     * @returns any Created
     * @throws ApiError
     */
    public static postApiArtifactsEditRebuild(
        requestBody?: string,
    ): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/artifacts/edit/rebuild',
            body: requestBody,
            mediaType: 'text/plain',
        });
    }
}

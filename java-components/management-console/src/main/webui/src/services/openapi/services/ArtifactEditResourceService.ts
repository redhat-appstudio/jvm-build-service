/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { EditResult } from '../models/EditResult';
import type { ModifyScmRepoCommand } from '../models/ModifyScmRepoCommand';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ArtifactEditResourceService {
    /**
     * @param requestBody
     * @returns EditResult OK
     * @throws ApiError
     */
    public static postApiArtifactsEdit(
        requestBody?: ModifyScmRepoCommand,
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

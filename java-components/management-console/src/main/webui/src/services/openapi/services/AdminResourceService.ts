/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class AdminResourceService {
    /**
     * @returns any Created
     * @throws ApiError
     */
    public static postApiAdminCleanOutDatabase(): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/admin/clean-out-database',
        });
    }
    /**
     * @returns any Created
     * @throws ApiError
     */
    public static postApiAdminClearBuildQueue(): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/admin/clear-build-queue',
        });
    }
    /**
     * @returns any Created
     * @throws ApiError
     */
    public static postApiAdminRebuildAll(): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/admin/rebuild-all',
        });
    }
    /**
     * @returns any Created
     * @throws ApiError
     */
    public static postApiAdminRebuildFailed(): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/admin/rebuild-failed',
        });
    }
}

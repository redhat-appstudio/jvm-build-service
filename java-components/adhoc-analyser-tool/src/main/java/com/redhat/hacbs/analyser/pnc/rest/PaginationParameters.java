package com.redhat.hacbs.analyser.pnc.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

public class PaginationParameters {

    @QueryParam(value = SwaggerConstants.PAGE_INDEX_QUERY_PARAM)
    @DefaultValue(value = SwaggerConstants.PAGE_INDEX_DEFAULT_VALUE)
    @PositiveOrZero
    protected int pageIndex;

    @QueryParam(value = SwaggerConstants.PAGE_SIZE_QUERY_PARAM)
    @DefaultValue(value = SwaggerConstants.PAGE_SIZE_DEFAULT_VALUE)
    @Positive
    @Max(value = SwaggerConstants.MAX_PAGE_SIZE)
    protected int pageSize;

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}

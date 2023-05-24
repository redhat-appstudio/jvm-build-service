package com.redhat.hacbs.analyser.pnc.rest;

import jakarta.ws.rs.QueryParam;

/**
 * Parameters for querying and sorting lists.
 *
 * @author Honza Brázdil &lt;jbrazdil@redhat.com&gt;
 */
public class PageParameters extends PaginationParameters {

    /**
     * {@value SwaggerConstants#SORTING_DESCRIPTION}
     */
    @QueryParam(SwaggerConstants.SORTING_QUERY_PARAM)
    private String sort;

    /**
     * {@value SwaggerConstants#QUERY_DESCRIPTION}
     */
    @QueryParam(SwaggerConstants.QUERY_QUERY_PARAM)
    private String q;

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public String getQ() {
        return q;
    }

    public void setQ(String q) {
        this.q = q;
    }
}

package com.redhat.hacbs.analyser.pnc.rest;

import java.util.Collection;
import java.util.Collections;

/**
 * Collection REST response.
 *
 * @author Honza Brázdil &lt;jbrazdil@redhat.com&gt;
 */
public class Page<T> {

    /**
     * Page index.
     */
    private int pageIndex;

    /**
     * Number of records per page.
     */
    private int pageSize;

    /**
     * Total pages provided by this query or -1 if unknown.
     */
    private int totalPages;

    /**
     * Number of all hits (not only this page).
     */
    private int totalHits;

    /**
     * Embedded collection of data.
     */
    private Collection<T> content;

    public Page() {
        content = Collections.emptyList();
    }

    public Page(int pageIndex, int pageSize, int totalHits, Collection<T> content) {
        this.pageIndex = pageIndex;
        this.pageSize = pageSize;
        this.totalPages = (int) Math.ceil((double) totalHits / pageSize);
        this.totalHits = totalHits;
        this.content = content;
    }

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

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public int getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(int totalHits) {
        this.totalHits = totalHits;
    }

    public Collection<T> getContent() {
        return content;
    }

    public void setContent(Collection<T> content) {
        this.content = content;
    }
}

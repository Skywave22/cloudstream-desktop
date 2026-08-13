package com.cloudstream.desktop.ui.screens

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchScreenTest {
    @Test
    fun `search uses the paginated provider API`() = runBlocking {
        val provider = PaginatedOnlyProvider()

        val result = provider.searchSafely("query")

        assertTrue(provider.paginatedSearchCalled)
        assertEquals(listOf("Result"), result.items.map(SearchResponse::name))
        assertNull(result.error)
    }

    private class PaginatedOnlyProvider : MainAPI() {
        override var name: String = "Paginated"
        var paginatedSearchCalled: Boolean = false

        override suspend fun search(query: String, page: Int): SearchResponseList {
            paginatedSearchCalled = true
            return newSearchResponseList(
                listOf(newMovieSearchResponse("Result", "https://example.com/result", fix = false)),
                hasNext = false,
            )
        }

        override suspend fun search(query: String): List<SearchResponse> {
            error("The non-paginated overload must not be called")
        }
    }
}

package com.example.android.codelabs.paging.ui

import android.util.Log
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingSource
import androidx.paging.RemoteMediator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan

/**
 * An enum representing the status of items in the as fetched by the
 * [Pager] when used with a [RemoteMediator]
 */
enum class RemotePresentationState {
    INITIAL, REMOTE_LOADING, SOURCE_LOADING, PRESENTED
}

/**
 * Reduces [CombinedLoadStates] into [RemotePresentationState]. It operates ton the assumption that
 * successful [RemoteMediator] fetches always cause invalidation of the [PagingSource] as in the
 * case of the [PagingSource] provide by Room.
 */

/*TODO ===> Scan */

/*There is an alternative to fold called scan.
 It is an todo intermediate operation that produces all intermediate accumulator values.*/

fun main24() {
    val list = listOf(1, 2, 3, 4)
    val res = list.scan(0) { acc, i -> acc + i }
    println(res) // [0, 1, 3, 6, 10]
}

/*[0, 1, 3, 6, 10]*/


/*CombinedLoadStates :

Collection of pagination LoadStates for both a PagingSource, and RemoteMediator.

Note: The REFRESHLoadType.REFRESH always has LoadState.endOfPaginationReached set to false.

TODO loadStateFlow

        For the shouldScrollToTop flag,
        the todo emissions of PagingDataAdapter.loadStateFlow are synchronous with
        what is displayed in the UI, so it's safe to immediately call list.scrollToPosition(0)
        as soon as the boolean flag emitted is true.

TODO The type in a LoadStateFlow is a CombinedLoadStates object.

CombinedLoadStates todo allows us to get the load state for the three different types of load operations:

TODO=> CombinedLoadStates.refresh


represents the load state for loading the PagingData for the first time
.
TODO=> CombinedLoadStates.prepend

represents the load state for loading data at the start of the list.

TODO=> CombinedLoadStates.append

 represents the load state for loading data at the end of the list.


*/

@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<CombinedLoadStates>.asRemotePresentationState(): Flow<RemotePresentationState> =
    scan(RemotePresentationState.INITIAL) { state, loadState ->
        Log.d("CombinedLoadStates", "asRemotePresentationState-> state:$state loadState$loadState")
        when (state) {
            RemotePresentationState.PRESENTED -> when (loadState.mediator?.refresh) {
                is LoadState.Loading -> RemotePresentationState.REMOTE_LOADING
                else -> state
            }
            RemotePresentationState.INITIAL -> when (loadState.mediator?.refresh) {
                is LoadState.Loading -> RemotePresentationState.REMOTE_LOADING
                else -> state
            }
            RemotePresentationState.REMOTE_LOADING -> when (loadState.source.refresh) {
                is LoadState.Loading -> RemotePresentationState.SOURCE_LOADING
                else -> state
            }
            RemotePresentationState.SOURCE_LOADING -> when (loadState.source.refresh) {
                is LoadState.NotLoading -> RemotePresentationState.PRESENTED
                else -> state
            }
        }
    }
        .distinctUntilChanged()
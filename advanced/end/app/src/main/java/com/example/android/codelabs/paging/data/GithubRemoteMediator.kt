/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.codelabs.paging.data

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.example.android.codelabs.paging.api.GithubService
import com.example.android.codelabs.paging.api.IN_QUALIFIER
import com.example.android.codelabs.paging.db.RemoteKeys
import com.example.android.codelabs.paging.db.RepoDatabase
import com.example.android.codelabs.paging.model.Repo
import com.example.android.codelabs.paging.ui.TAG
import retrofit2.HttpException
import java.io.IOException

// GitHub page API is 1 based: https://developer.github.com/v3/#pagination
private const val GITHUB_STARTING_PAGE_INDEX = 1

@OptIn(ExperimentalPagingApi::class)
class GithubRemoteMediator(
    private val query: String,
    private val service: GithubService,
    private val repoDatabase: RepoDatabase
) : RemoteMediator<Int, Repo>() {

    override suspend fun initialize(): InitializeAction {
        // Launch remote refresh as soon as paging starts and do not trigger remote prepend or
        // append until refresh has succeeded. In cases where we don't mind showing out-of-date,
        // cached offline data, we can return SKIP_INITIAL_REFRESH instead to prevent paging
        // triggering remote refresh.
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(loadType: LoadType, state: PagingState<Int, Repo>): MediatorResult {

        /*TODO Loading scenario First Open

But loading scenario is not same as Paging source in this request hit two time in first open

 TODO the load method in remote mediator

  has 2 parameters that should give us all the information we need:

TODO PagingState -

 this gives us information about the pages that were loaded before,
  the most recently accessed index in the list, and the
  PagingConfig we defined when initializing the paging stream.

TODO LoadType -

TODO APPEND

this tells us whether we need to load data at the end ( LoadType.APPEND)

 TODO PREPEND

or at the beginning of the data (LoadType.PREPEND) that we previously loaded,

TODO REFRESH

or if this the first time we're loading data (LoadType.REFRESH).

For example, if the load type is LoadType.APPEND then we retrieve the last item
that was loaded from the PagingState. Based on that we should be able to find out
 to load the next batch of Repo objects, by computing the next page to be loaded.*/

        val page = when (loadType) {
            LoadType.REFRESH -> {
                /*LoadType.REFRESH gets called
                 when it's the first time we're loading data, or
                when PagingDataAdapter.refresh() is called;
                so now the point of reference for loading our data is the state.anchorPosition.*/

                val remoteKeys = getRemoteKeyClosestToCurrentPosition(state)
                /*If remoteKey is not null, then we can get the nextKey from it.
                In the Github API the page keys are incremented sequentially.
                So to get the page that contains the current item, we just subtract 1 from remoteKey.nextKey.

                If RemoteKey is null (because the anchorPosition was null),
                then the page we need to load is the initial one: GITHUB_STARTING_PAGE_INDEX*/
                Log.d(TAG, "load-> LoadType.REFRESH-> nextKey: ${ remoteKeys?.nextKey}")
                remoteKeys?.nextKey?.minus(1) ?: GITHUB_STARTING_PAGE_INDEX
            }
            LoadType.PREPEND -> {
                /*When we need to load data at the todo beginning of the currently loaded data set,
                the load parameter is LoadType.PREPEND.*/
                val remoteKeys = getRemoteKeyForFirstItem(state)
                // If remoteKeys is null, that means the refresh result is not in the database yet.
                // We can return Success with `endOfPaginationReached = false` because Paging
                // will call this method again if RemoteKeys becomes non-null.
                // If remoteKeys is NOT NULL but its prevKey is null, that means we've reached
                // the end of pagination for prepend.
                val prevKey = remoteKeys?.prevKey
                if (prevKey == null) {
                    return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                }
                Log.d(TAG, "load-> LoadType.PREPEND-> nextKey: $prevKey")
                prevKey
            }
            LoadType.APPEND -> {
                /*When we need to load data at the end of the currently loaded data set, the load parameter is LoadType.APPEND.*/
                val remoteKeys = getRemoteKeyForLastItem(state)
                // If remoteKeys is null, that todo means the refresh result is not in the database yet.
                // We can return Success with `endOfPaginationReached = false` because Paging
                // will call this method again if RemoteKeys becomes non-null.
                // todo If remoteKeys is NOT NULL but its nextKey is null, that means we've reached
                // the end of pagination for append.
                val nextKey = remoteKeys?.nextKey
                if (nextKey == null) {
                    return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                }

                Log.d(TAG, "load-> LoadType.APPEND-> nextKey: $nextKey")
                nextKey
            }
        }

        Log.d(TAG, "load key: $page")
        Log.d(TAG, "Page Size : ${state.config.pageSize}")
        Log.d(TAG, "Prefetch Distance : ${state.config.prefetchDistance}")
        Log.d(TAG, "Initial Load Size : ${state.config.initialLoadSize}")

        val apiQuery = query + IN_QUALIFIER

        try {
            val apiResponse = service.searchRepos(apiQuery, page, state.config.pageSize)

            val repos = apiResponse.items
            val endOfPaginationReached = repos.isEmpty()
            Log.d(TAG, "load->repos size :${repos.size} ")
            repoDatabase.withTransaction {
                // clear all tables in the database
                if (loadType == LoadType.REFRESH) {
                    Log.d(TAG, "load->repoDatabase.withTransaction :clearRemoteKeys and clearRepos")
                    repoDatabase.remoteKeysDao().clearRemoteKeys()
                    repoDatabase.reposDao().clearRepos()
                }
                val prevKey = if (page == GITHUB_STARTING_PAGE_INDEX) null else page - 1
                val nextKey = if (endOfPaginationReached) null else page + 1

                Log.d(TAG, "load->After Api response :prevKey $prevKey and nextKey $nextKey")
                val keys = repos.map {
                    RemoteKeys(repoId = it.id, prevKey = prevKey, nextKey = nextKey)
                }

                Log.d(TAG, "load->After Api response :keys $keys")

                Log.d(TAG, "load->After Api response :endOfPaginationReached $endOfPaginationReached")
                repoDatabase.remoteKeysDao().insertAll(keys)
                repoDatabase.reposDao().insertAll(repos)
            }
            return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (exception: IOException) {
            return MediatorResult.Error(exception)
        } catch (exception: HttpException) {
            return MediatorResult.Error(exception)
        }
    }

    private suspend fun getRemoteKeyForLastItem(state: PagingState<Int, Repo>): RemoteKeys? {
        // Get the last page that was retrieved, that contained items.
        // From that last page, get the last item
        return state.pages.lastOrNull() { it.data.isNotEmpty() }?.data?.lastOrNull()
            ?.let { repo ->
                // Get the remote keys of the last item retrieved
                repoDatabase.remoteKeysDao().remoteKeysRepoId(repo.id)
            }
    }

    private suspend fun getRemoteKeyForFirstItem(state: PagingState<Int, Repo>): RemoteKeys? {
        // Get the first page that was retrieved, that contained items.
        // From that first page, get the first item
        return state.pages.firstOrNull { it.data.isNotEmpty() }?.data?.firstOrNull()
            ?.let { repo ->
                // Get the remote keys of the first items retrieved
                repoDatabase.remoteKeysDao().remoteKeysRepoId(repo.id)
            }
    }

    private suspend fun getRemoteKeyClosestToCurrentPosition(
        state: PagingState<Int, Repo>
    ): RemoteKeys? {
        // The paging library is trying to load data after the anchor position
        // Get the item closest to the anchor position
        return state.anchorPosition?.let { position ->
            state.closestItemToPosition(position)?.id?.let { repoId ->
                repoDatabase.remoteKeysDao().remoteKeysRepoId(repoId)
            }
        }
    }
}


/*
2022-12-08 11:52:55.024 16929-16929  View Model -> state flow: search Search(query=Android) scroll Scroll(currentQuery=Android)
2022-12-08 11:52:55.029 16929-16929  ViewModel-> pagingDataFlow->flatMapLatest-> Query Android 
2022-12-08 11:52:55.029 16929-16929  searchRepo: called :queryString Android 
2022-12-08 11:52:55.061 16929-16929  bindList-> shouldScroll false
2022-12-08 11:52:55.062 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:55.249 16929-16929  load-> LoadType.REFRESH-> nextKey: null
2022-12-08 11:52:55.249 16929-16929  load key: 1
2022-12-08 11:52:55.249 16929-16929  Page Size : 20
2022-12-08 11:52:55.249 16929-16929  Prefetch Distance : 5
2022-12-08 11:52:55.249 16929-16929  Initial Load Size : 20
2022-12-08 11:52:55.279 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:55.337 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:56.831 16929-16929  load->repos size :20 
2022-12-08 11:52:56.832 16929-17082  load->repoDatabase.withTransaction :clearRemoteKeys and clearRepos
2022-12-08 11:52:56.834 16929-17082  load->After Api response :prevKey null and nextKey 2
2022-12-08 11:52:56.835 16929-17082  load->After Api response :keys [RemoteKeys(repoId=111583593, prevKey=null, nextKey=2), RemoteKeys(repoId=12256376, prevKey=null, nextKey=2), RemoteKeys(repoId=28428729, prevKey=null, nextKey=2), RemoteKeys(repoId=5152285, prevKey=null, nextKey=2), RemoteKeys(repoId=51148780, prevKey=null, nextKey=2), RemoteKeys(repoId=892275, prevKey=null, nextKey=2), RemoteKeys(repoId=27442967, prevKey=null, nextKey=2), RemoteKeys(repoId=19148949, prevKey=null, nextKey=2), RemoteKeys(repoId=299354207, prevKey=null, nextKey=2), RemoteKeys(repoId=70198875, prevKey=null, nextKey=2), RemoteKeys(repoId=79162682, prevKey=null, nextKey=2), RemoteKeys(repoId=11267509, prevKey=null, nextKey=2), RemoteKeys(repoId=7190986, prevKey=null, nextKey=2), RemoteKeys(repoId=64558143, prevKey=null, nextKey=2), RemoteKeys(repoId=15653276, prevKey=null, nextKey=2), RemoteKeys(repoId=10446890, prevKey=null, nextKey=2), RemoteKeys(repoId=67702184, prevKey=null, nextKey=2), RemoteKeys(repoId=2562751, prevKey=null, nextKey=2), RemoteKeys(repoId=34824499, prevKey=null, nextKey=2), RemoteKeys(repoId=10788737, prevKey=null, nextKey=2)]
2022-12-08 11:52:56.835 16929-17082  load->After Api response :endOfPaginationReached false
2022-12-08 11:52:56.841 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=Loading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=Loading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:56.851 16929-16929  bindList-> pagingData:androidx.paging.PagingData@6340fff 
2022-12-08 11:52:56.856 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=Loading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=Loading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:56.879 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=Loading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:56.897 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=true)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:56.899 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=Loading(endOfPaginationReached=false), source=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=true)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=Loading(endOfPaginationReached=false)))
2022-12-08 11:52:56.899 16929-16929  load-> LoadType.APPEND-> nextKey: 2
2022-12-08 11:52:56.899 16929-16929  load key: 2
2022-12-08 11:52:56.899 16929-16929  Page Size : 20
2022-12-08 11:52:56.899 16929-16929  Prefetch Distance : 5
2022-12-08 11:52:56.899 16929-16929  Initial Load Size : 20
2022-12-08 11:52:58.079 16929-16929  load->repos size :20 
2022-12-08 11:52:58.081 16929-17079  load->After Api response :prevKey 1 and nextKey 3
2022-12-08 11:52:58.081 16929-17079  load->After Api response :keys [RemoteKeys(repoId=31085130, prevKey=1, nextKey=3), RemoteKeys(repoId=23783375, prevKey=1, nextKey=3), RemoteKeys(repoId=8575137, prevKey=1, nextKey=3), RemoteKeys(repoId=5070389, prevKey=1, nextKey=3), RemoteKeys(repoId=93152223, prevKey=1, nextKey=3), RemoteKeys(repoId=2990192, prevKey=1, nextKey=3), RemoteKeys(repoId=90792131, prevKey=1, nextKey=3), RemoteKeys(repoId=41889031, prevKey=1, nextKey=3), RemoteKeys(repoId=18347476, prevKey=1, nextKey=3), RemoteKeys(repoId=13862381, prevKey=1, nextKey=3), RemoteKeys(repoId=5373551, prevKey=1, nextKey=3), RemoteKeys(repoId=22374063, prevKey=1, nextKey=3), RemoteKeys(repoId=20818126, prevKey=1, nextKey=3), RemoteKeys(repoId=43807251, prevKey=1, nextKey=3), RemoteKeys(repoId=23095954, prevKey=1, nextKey=3), RemoteKeys(repoId=26102180, prevKey=1, nextKey=3), RemoteKeys(repoId=56315715, prevKey=1, nextKey=3), RemoteKeys(repoId=3755875, prevKey=1, nextKey=3), RemoteKeys(repoId=221809776, prevKey=1, nextKey=3), RemoteKeys(repoId=10057936, prevKey=1, nextKey=3)]
2022-12-08 11:52:58.081 16929-17079  load->After Api response :endOfPaginationReached false
2022-12-08 11:52:58.088 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=true)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:58.094 16929-16929  bindList-> pagingData:androidx.paging.PagingData@354c1c2 
2022-12-08 11:52:58.099 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=Loading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=false), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)))
2022-12-08 11:52:58.115 16929-16929  bindList->repoAdapter.loadStateFlow: CombinedLoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false), source=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)), mediator=LoadStates(refresh=NotLoading(endOfPaginationReached=false), prepend=NotLoading(endOfPaginationReached=true), append=NotLoading(endOfPaginationReached=false)))
*/

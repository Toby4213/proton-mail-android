/*
 * Copyright (c) 2020 Proton Technologies AG
 * 
 * This file is part of ProtonMail.
 * 
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.api

import ch.protonmail.android.api.interceptors.RetrofitTag
import ch.protonmail.android.api.models.RefreshBody
import ch.protonmail.android.api.models.RefreshResponse
import ch.protonmail.android.api.segments.RetrofitConstants.ACCEPT_HEADER_V1
import ch.protonmail.android.api.segments.RetrofitConstants.CONTENT_TYPE
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Tag

/**
 * Created by dkadrikj on 12/5/16.
 */

interface ProtonMailPublicService {

    //region auth
    @POST("auth/refresh")
    @Headers(CONTENT_TYPE, ACCEPT_HEADER_V1)
    fun refreshSync(@Body refreshBody: RefreshBody, @Tag retrofitTag: RetrofitTag? = null): Call<RefreshResponse>
    //endregion
}

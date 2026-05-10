package com.awr.downloaderstudio
data class DownloadTask(val id:Long,val name:String,var progress:Int=0,var status:String="Starting",var totalText:String="",var path:String="")

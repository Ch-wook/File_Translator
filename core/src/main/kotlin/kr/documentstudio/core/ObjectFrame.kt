package kr.documentstudio.core

import kotlin.math.*

/** Document coordinates; hit tests and opposite-corner resize work on rotated objects too. */
data class ObjectFrame(val x: Float,val y: Float,val width: Float,val height: Float,val rotation: Float=0f) {
    fun local(px: Float,py: Float): Pair<Float,Float> {
        val a=Math.toRadians(-rotation.toDouble());val dx=px-x-width/2;val dy=py-y-height/2
        return (dx*cos(a)-dy*sin(a)).toFloat()+width/2 to (dx*sin(a)+dy*cos(a)).toFloat()+height/2
    }
    fun contains(px: Float,py: Float): Boolean {val (u,v)=local(px,py);return u in 0f..width && v in 0f..height}
    fun resize(px: Float,py: Float,left: Boolean,top: Boolean,minSize: Float): ObjectFrame {
        val (u,v)=local(px,py)
        val l=if(left) u.coerceAtMost(width-minSize) else 0f
        val r=if(left) width else u.coerceAtLeast(minSize)
        val t=if(top) v.coerceAtMost(height-minSize) else 0f
        val b=if(top) height else v.coerceAtLeast(minSize)
        val a=Math.toRadians(rotation.toDouble());val cx=(l+r-width)/2;val cy=(t+b-height)/2
        val centerX=x+width/2+(cx*cos(a)-cy*sin(a)).toFloat()
        val centerY=y+height/2+(cx*sin(a)+cy*cos(a)).toFloat()
        return copy(x=(centerX-(r-l)/2).coerceAtLeast(0f),y=(centerY-(b-t)/2).coerceAtLeast(0f),width=r-l,height=b-t)
    }
}

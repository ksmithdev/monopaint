#include <jni.h>
#include <stdint.h>
#include <math.h>

/* Bilinear resample of the placed reference image into a page rect, in premultiplied
 * gray so turned edges stay clean. Mirrors ReferenceImage.raster byte for byte.
 * The tablet's Java runtime needs ~190 ns/page-pixel for this loop, far too slow to
 * drag a page-sized picture, so it lives beside the display's own pixel loops. */
JNIEXPORT void JNICALL
Java_io_github_mpdairy_monopaint_ReferenceImage_nativeRaster(JNIEnv *env,jclass clazz,
        jbyteArray lum,jbyteArray alpha,jint imageWidth,jint imageHeight,jfloatArray transform,
        jbyteArray tones,jbyteArray coverage,jint pageWidth,jint pageHeight,
        jint left,jint top,jint right,jint bottom) {
    (void)clazz;
    jsize pixels=(jsize)imageWidth*imageHeight;
    if(imageWidth<1 || imageHeight<1 || pixels<1
            || (*env)->GetArrayLength(env,lum)!=pixels
            || (*env)->GetArrayLength(env,alpha)!=pixels
            || (*env)->GetArrayLength(env,transform)!=6
            || (*env)->GetArrayLength(env,tones)!=(jsize)pageWidth*pageHeight
            || (*env)->GetArrayLength(env,coverage)!=(jsize)pageWidth*pageHeight) {
        return; /* bad arrays: write nothing */
    }
    if(left<0)left=0; if(top<0)top=0;
    if(right>pageWidth)right=pageWidth; if(bottom>pageHeight)bottom=pageHeight;
    if(right<=left || bottom<=top) return;
    jfloat m[6];
    (*env)->GetFloatArrayRegion(env,transform,0,6,m);
    double det=(double)m[0]*m[4]-(double)m[1]*m[3];
    int degenerate=!isfinite(det) || fabs(det)<1e-9;
    const jbyte *srcLum=(*env)->GetByteArrayElements(env,lum,NULL);
    const jbyte *srcAlpha=(*env)->GetByteArrayElements(env,alpha,NULL);
    jbyte *outTones=(*env)->GetByteArrayElements(env,tones,NULL);
    jbyte *outCoverage=(*env)->GetByteArrayElements(env,coverage,NULL);
    if(!srcLum || !srcAlpha || !outTones || !outCoverage) {
        if(srcLum)(*env)->ReleaseByteArrayElements(env,lum,(void *)srcLum,JNI_ABORT);
        if(srcAlpha)(*env)->ReleaseByteArrayElements(env,alpha,(void *)srcAlpha,JNI_ABORT);
        if(outTones)(*env)->ReleaseByteArrayElements(env,tones,outTones,JNI_ABORT);
        if(outCoverage)(*env)->ReleaseByteArrayElements(env,coverage,outCoverage,JNI_ABORT);
        return;
    }
    for(jint y=top;y<bottom;y++) for(jint x=left;x<right;x++) {
        jsize at=(jsize)y*pageWidth+x;
        /* Degenerate placement: clear the rect to paper. */
        if(degenerate) { outTones[at]=(jbyte)255; outCoverage[at]=0; continue; }
        /* Page pixel center back through the placement; image pixels span
         * [-0.5, w-0.5], so drop a half pixel before filtering. */
        double rx=x+0.5-(double)m[2], ry=y+0.5-(double)m[5];
        double u=((double)m[4]*rx-(double)m[1]*ry)/det-0.5;
        double v=(-(double)m[3]*rx+(double)m[0]*ry)/det-0.5;
        if(u<-0.5 || u>imageWidth-0.5 || v<-0.5 || v>imageHeight-0.5) {
            outTones[at]=(jbyte)255; outCoverage[at]=0; continue;
        }
        jint x0=(jint)floor(u), y0=(jint)floor(v), x1=x0+1, y1=y0+1;
        double fx=u-x0, fy=v-y0;
        if(x0<0)x0=0; if(x1>imageWidth-1)x1=imageWidth-1;
        if(y0<0)y0=0; if(y1>imageHeight-1)y1=imageHeight-1;
        double w00=(1-fx)*(1-fy), w10=fx*(1-fy), w01=(1-fx)*fy, w11=fx*fy;
        jsize i00=(jsize)y0*imageWidth+x0, i10=(jsize)y0*imageWidth+x1;
        jsize i01=(jsize)y1*imageWidth+x0, i11=(jsize)y1*imageWidth+x1;
        double a00=(double)(srcAlpha[i00]&255), a10=(double)(srcAlpha[i10]&255);
        double a01=(double)(srcAlpha[i01]&255), a11=(double)(srcAlpha[i11]&255);
        double sum=w00*a00+w10*a10+w01*a01+w11*a11;
        if(sum<0.5) { outTones[at]=(jbyte)255; outCoverage[at]=0; continue; }
        double pigment=w00*a00*(double)(srcLum[i00]&255)+w10*a10*(double)(srcLum[i10]&255)
                +w01*a01*(double)(srcLum[i01]&255)+w11*a11*(double)(srcLum[i11]&255);
        jint tone=(jint)round(pigment/sum);
        if(tone<0)tone=0; if(tone>255)tone=255;
        jint cover=(jint)round(sum);
        if(cover<0)cover=0; if(cover>255)cover=255;
        outTones[at]=(jbyte)tone; outCoverage[at]=(jbyte)cover;
    }
    (*env)->ReleaseByteArrayElements(env,lum,(void *)srcLum,JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env,alpha,(void *)srcAlpha,JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env,tones,outTones,0);
    (*env)->ReleaseByteArrayElements(env,coverage,outCoverage,0);
}

#include <jni.h>
#include <grp.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <string>

extern "C" JNIEXPORT void JNICALL
Java_com_tomtom_installer_NativeShellCredentials_clearGroups(JNIEnv* env, jobject) {
    if (setgroups(0, nullptr) != 0) {
        const std::string message = std::string("Impossible de retirer les groupes root : ")
            + std::strerror(errno);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
    }
}

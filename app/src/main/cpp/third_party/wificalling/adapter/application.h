#pragma once
// Android stub version of application.h
// 目的：在 Android 先不連 libcurl/OpenSSL，讓其他檔（例如 session.cpp）能順利編譯、連結。
// 這個檔會因為 CMake include path 順序而覆蓋原本的 include/application.h。

#include <string>
#include <vector>
#include <utility>

class Application {
public:
  // 你的原始程式大多用 Application(session 或其他參數) 建構；這裡用可變參數模板接受任何型別。
  template <typename... Args>
  explicit Application(Args&&... /*unused*/) {}

  // ----- 以下是 session.cpp 目前報錯缺少的介面 -----

  // application.CallDoS(pfd, nReady, victimList[0]);
  template <typename... Args>
  void CallDoS(Args&&... /*unused*/) {
    // stub: no-op
  }

  // application.CallDetect(pfd, nReady, victimList[0]);
  template <typename... Args>
  void CallDetect(Args&&... /*unused*/) {
    // stub: no-op
  }

  // application.startRemoteCallServer(pfd, nReady, victimList);
  template <typename... Args>
  void startRemoteCallServer(Args&&... /*unused*/) {
    // stub: no-op
  }

  // 若未來還有其他成員被呼叫，照樣在這裡加同名空方法即可。
  // 例如：
  // void startRegistration(...) {}
  // void onSipMessage(...) {}
  // std::string buildInvite(...) { return {}; }

  // ----- 靜態工具：Application::extractCellularInfo(fullbody, state.calleeId);
  template <typename... Args>
  static void extractCellularInfo(Args&&... /*unused*/) {
    // stub: no-op
  }
};

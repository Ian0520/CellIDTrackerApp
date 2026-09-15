#pragma once

#include <poll.h>

#include <string>

class Application;
class Session;

class ProbeController {
public:
  ProbeController(Application& application, Session& session)
      : application(application), session(session) {}

  void run(pollfd& pfd, int nReady, const std::string& calleeId);

private:
  Application& application;
  Session& session;
};

// Definitions matching Net.h, plus the shapes that only appear in a .cpp.
#include "Net.h"

namespace game {
namespace net {

int retries = 3;                //= 아님
const char* kVersion = "1.0";   //= 아님

Session::Session() = default;                     //= 함수

Session::Session(int id) : id_(id) {}             //= 함수

Session::~Session() {}                            //= 함수

bool Session::Handle(const Packet& p) {           //= 함수
    return p.id != 0;
}

bool Session::Handle(const Packet& p, int retries) {   //= 함수
    for (int i = 0; i < retries; ++i) {
        if (Handle(p)) return true;
    }
    return false;
}

bool Session::Handle(const std::vector<Packet>& batch) {   //= 함수
    for (const auto& p : batch) {
        if (!Handle(p)) return false;
    }
    return true;
}

bool Session::Authenticate(                       //= 함수
    const std::string& account,
    const std::string& token,
    int attempts) {
    return !account.empty() && !token.empty() && attempts > 0;
}

Session* Session::Create(int id) {                //= 함수
    return new Session(id);
}

Session& Session::operator=(const Session& other) {   //= 함수
    id_ = other.id_;
    return *this;
}

bool Session::operator==(const Session& other) const {   //= 함수
    return id_ == other.id_;
}

// Defined through the same macro that declared it.
DECLARE_HANDLER(Session::HandleLogin) {           //= 함수
    return p.id == 1001;
}

template <typename T>
void Queue<T>::Push(const T& item) {              //= 함수
    items_.push_back(item);
}

template <typename T>
T Queue<T>::Pop() {                               //= 함수
    T item = items_.back();
    items_.pop_back();
    return item;
}

template <typename T>
T Clamp(T value, T low, T high) {                 //= 함수
    return value < low ? low : (value > high ? high : value);
}

bool Send(const Packet& p) {                      //= 함수
    return p.id > 0;
}

void Log(const char* message, ...) {              //= 함수
}

// A lambda assigned to a variable: a body with parameters, but not a declaration of a function.
static auto kFilter = [](const Packet& p) { return p.id != 0; };   //= 아님

}  // namespace net
}  // namespace game

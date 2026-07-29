// Declarations that are easy to get wrong.
//
// Every line the backend should report as a function carries a trailing marker comment, and every line
// that looks like one but is not carries the opposite marker. check-symbols.sh compares the backend's
// answer against those markers, so a regression shows up as a named line rather than a number that moved.
// (This paragraph deliberately spells none of them out — the checker reads markers, including these.)
#pragma once

#include <string>
#include <vector>

#define DECLARE_HANDLER(name) bool name(const Packet& p)   //= 아님

namespace game {
namespace net {

struct Packet {
    int id;
    std::string body;
};

using Callback = bool (*)(const Packet&);   //= 아님

class Session {
public:
    Session();                                        //= 함수
    explicit Session(int id);                         //= 함수
    virtual ~Session();                               //= 함수

    bool Handle(const Packet& p);                     //= 함수
    bool Handle(const Packet& p, int retries);        //= 함수
    bool Handle(const std::vector<Packet>& batch);    //= 함수

    // A signature that does not fit on one line.
    bool Authenticate(                                //= 함수
        const std::string& account,
        const std::string& token,
        int attempts);

    virtual void Close() = 0;                         //= 함수
    static Session* Create(int id);                   //= 함수
    inline int Id() const { return id_; }             //= 함수
    constexpr bool Empty() const { return id_ == 0; } //= 함수

    Session& operator=(const Session& other);         //= 함수
    bool operator==(const Session& other) const;      //= 함수

    DECLARE_HANDLER(HandleLogin);                     //= 함수

private:
    int id_ = 0;
    std::vector<Packet> queue_;                       //= 아님
    Callback callback_ = nullptr;                     //= 아님
};

template <typename T>
class Queue {
public:
    void Push(const T& item);                         //= 함수
    T Pop();                                          //= 함수
    bool Empty() const { return items_.empty(); }     //= 함수

private:
    std::vector<T> items_;                            //= 아님
};

template <typename T>
T Clamp(T value, T low, T high);                      //= 함수

bool Send(const Packet& p);                           //= 함수
void Log(const char* message, ...);                   //= 함수

// The "most vexing parse": this declares a function, not a variable. Whatever the backend answers, it
// should answer the same thing every time — this line exists to pin that down.
Session MakeSession(Packet());                        //= 함수

extern int retries;                                   //= 아님
extern const char* kVersion;                          //= 아님

}  // namespace net
}  // namespace game

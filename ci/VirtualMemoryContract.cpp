#include "virtual_memory.h"

#include <cstdio>
#include <stdexcept>

#ifdef _WIN32
#include <windows.h>
#endif

static void check(bool value, const char *message) {
    if (!value) throw std::runtime_error(message);
}

int main() {
    constexpr size_t PAGE = quickjs_wrapper::VirtualMemory::WASM_PAGE_SIZE;
    constexpr size_t CAPACITY = 64 * 1024 * 1024;
    try {
        auto memory = quickjs_wrapper::VirtualMemory::allocate(CAPACITY, PAGE);
        check(memory != nullptr, "Initial reservation failed");
        uint8_t *address = memory->data();
        check(memory->size() == PAGE && memory->capacity() == CAPACITY, "Initial memory limits");
        address[0] = 42;
#ifdef _WIN32
        MEMORY_BASIC_INFORMATION state{};
        check(VirtualQuery(address, &state, sizeof(state)) == sizeof(state), "Initial page query");
        check(state.State == MEM_COMMIT && state.RegionSize == PAGE, "Only initial pages are committed");
        check(VirtualQuery(address + PAGE, &state, sizeof(state)) == sizeof(state), "Reservation query");
        check(state.State == MEM_RESERVE, "Maximum capacity remains uncommitted");
#endif
        check(memory->grow(2 * PAGE), "Memory growth failed");
        check(memory->data() == address && address[0] == 42, "Growth relocated or lost existing data");
        for (size_t i = PAGE; i < 2 * PAGE; i++) check(address[i] == 0, "New pages are not zero");
        check(!memory->grow(PAGE) && !memory->grow(CAPACITY + PAGE) && !memory->grow(2 * PAGE + 1), "Invalid growth accepted");
        check(memory->size() == 2 * PAGE && address[0] == 42, "Failed growth changed live memory");
        memory.reset();
#ifdef _WIN32
        check(VirtualQuery(address, &state, sizeof(state)) == sizeof(state), "Released page query");
        check(state.State == MEM_FREE, "Reservation was not released");
#endif
        auto empty = quickjs_wrapper::VirtualMemory::allocate(1, 0);
        check(empty && empty->size() == 0 && empty->grow(0), "Zero-page memory");
        check(!empty->grow(PAGE), "Zero-maximum memory grew");
        std::puts("PASS: sparse reservation, OS commitment, stable growth, zero pages and release");
        return 0;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "Virtual-memory contract failed: %s\n", error.what());
        return 1;
    }
}

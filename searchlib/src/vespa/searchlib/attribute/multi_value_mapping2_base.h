// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchlib/datastore/entryref.h>
#include <vespa/searchlib/common/rcuvector.h>
#include <vespa/vespalib/stllike/hash_map.h>
#include <functional>

namespace search {

class CompactionStrategy;

namespace attribute {

/**
 * Base class for mapping from from document id to an array of values.
 */
class MultiValueMapping2Base
{
public:
    using EntryRef = datastore::EntryRef;
    using RefVector = RcuVectorBase<EntryRef>;

protected:
    RefVector _indices;
    size_t    _totalValues;
    MemoryUsage _cachedMemoryUsage;

    MultiValueMapping2Base(const GrowStrategy &gs, vespalib::GenerationHolder &genHolder);
    virtual ~MultiValueMapping2Base();

    void updateValueCount(size_t oldValues, size_t newValues) {
        _totalValues += newValues - oldValues;
    }
public:
    using RefCopyVector = vespalib::Array<EntryRef>;

    virtual MemoryUsage getMemoryUsage() const = 0;
    MemoryUsage updateMemoryUsage();
    size_t getTotalValueCnt() const { return _totalValues; }
    RefCopyVector getRefCopy(uint32_t size) const;

    bool isFull() const { return _indices.isFull(); }
    void addDoc(uint32_t &docId);
    void shrink(uint32_t docidLimit);
    void clearDocs(uint32_t lidLow, uint32_t lidLimit, std::function<void(uint32_t)> clearDoc);
    uint32_t size() const { return _indices.size(); }

    // Mockups to temporarily silence code written for old multivalue mapping
    class Histogram
    {
    private:
        using HistogramM = vespalib::hash_map<uint32_t, size_t>;
    public:
        using const_iterator = HistogramM::const_iterator;
        Histogram() : _histogram() { _histogram.insert({0, 0}); }
        size_t & operator [] (uint32_t) { return _histogram[0u]; }
        const_iterator begin() const { return _histogram.begin(); }
        const_iterator   end() const { return _histogram.end(); }
    private:
        HistogramM _histogram;
    };
    Histogram getEmptyHistogram() const { return Histogram(); }
    Histogram getRemaining() const { return Histogram(); }
    static size_t maxValues() { return 0; }
    uint32_t getNumKeys() const { return _indices.size(); }
    uint32_t getCapacityKeys() const { return _indices.capacity(); }
    virtual void compactWorst() = 0;
    bool considerCompact(const CompactionStrategy &compactionStrategy);
};

} // namespace search::attribute
} // namespace search

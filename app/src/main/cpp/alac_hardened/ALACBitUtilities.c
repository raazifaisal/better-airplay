/*
 * HARDENED COPY for better_airplay. Upstream: third_party/alac/codec (Apple, Apache-2.0).
 * Changes (marked HARDENED) make the decoder safe on untrusted network input without padding,
 * following FFmpeg's ALAC decoder: checked bit reads and a frame-length bound on the sample count.
 */
/*
 * Copyright (c) 2011 Apple Inc. All rights reserved.
 *
 * @APPLE_APACHE_LICENSE_HEADER_START@
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * @APPLE_APACHE_LICENSE_HEADER_END@
 */

/*=============================================================================
    File:		ALACBitUtilities.c

	$NoKeywords: $
=============================================================================*/

#include <stdio.h>
#include "ALACBitUtilities.h"

// HARDENED: checked byte fetch. Past the end of the packet the stream reads as zeros
// (what FFmpeg's padding provides), so no read leaves the caller's buffer.
static inline uint32_t byteAt( const BitBuffer * bits, uint32_t i )
{
	return ( bits->cur < bits->end && (size_t)( bits->end - bits->cur ) > i ) ? bits->cur[i] : 0;
}

// BitBufferInit
//
void BitBufferInit( BitBuffer * bits, uint8_t * buffer, uint32_t byteSize )
{
	bits->cur		= buffer;
	bits->end		= bits->cur + byteSize;
	bits->bitIndex	= 0;
	bits->byteSize	= byteSize;
}

// BitBufferRead
//
uint32_t BitBufferRead( BitBuffer * bits, uint8_t numBits )
{
	uint32_t		returnBits;
	
	//Assert( numBits <= 16 );

	returnBits = (byteAt( bits, 0 ) << 16) | (byteAt( bits, 1 ) << 8) | byteAt( bits, 2 );	// HARDENED
	returnBits = returnBits << bits->bitIndex;
	returnBits &= 0x00FFFFFF;
	
	bits->bitIndex += numBits;
	
	returnBits = returnBits >> (24 - numBits);
	
	bits->cur		+= (bits->bitIndex >> 3);
	bits->bitIndex	&= 7;
	
	//Assert( bits->cur <= bits->end );
	
	return returnBits;
}

// BitBufferReadSmall
//
// Reads up to 8 bits
uint8_t BitBufferReadSmall( BitBuffer * bits, uint8_t numBits )
{
	uint16_t		returnBits;
	
	//Assert( numBits <= 8 );
	
	returnBits = (uint16_t) ((byteAt( bits, 0 ) << 8) | byteAt( bits, 1 ));	// HARDENED
	returnBits = returnBits << bits->bitIndex;
	
	bits->bitIndex += numBits;
	
	returnBits = returnBits >> (16 - numBits);
	
	bits->cur		+= (bits->bitIndex >> 3);
	bits->bitIndex	&= 7;
	
	//Assert( bits->cur <= bits->end );
	
	return (uint8_t)returnBits;
}

// BitBufferReadOne
//
// Reads one byte
uint8_t BitBufferReadOne( BitBuffer * bits )
{
	uint8_t		returnBits;

	returnBits = (uint8_t) ((byteAt( bits, 0 ) >> (7 - bits->bitIndex)) & 1);	// HARDENED

	bits->bitIndex++;
	
	bits->cur		+= (bits->bitIndex >> 3);
	bits->bitIndex	&= 7;
	
	//Assert( bits->cur <= bits->end );
	
	return returnBits;
}

// BitBufferPeek
//
uint32_t BitBufferPeek( BitBuffer * bits, uint8_t numBits )
{
	return ((((byteAt( bits, 0 ) << 16) | (byteAt( bits, 1 ) << 8) |
			byteAt( bits, 2 )) << bits->bitIndex) & 0x00FFFFFF) >> (24 - numBits);	// HARDENED
}

// BitBufferPeekOne
//
uint32_t BitBufferPeekOne( BitBuffer * bits )
{
	return ((byteAt( bits, 0 ) >> (7 - bits->bitIndex)) & 1);	// HARDENED
}

// BitBufferUnpackBERSize
//
uint32_t BitBufferUnpackBERSize( BitBuffer * bits )
{
	uint32_t		size;
	uint8_t		tmp;
	
	for ( size = 0, tmp = 0x80u; tmp &= 0x80u; size = (size << 7u) | (tmp & 0x7fu) )
		tmp = (uint8_t) BitBufferReadSmall( bits, 8 );
	
	return size;
}

// BitBufferGetPosition
//
uint32_t BitBufferGetPosition( BitBuffer * bits )
{
	uint8_t *		begin;
	
	begin = bits->end - bits->byteSize;
	
	return ((uint32_t)(bits->cur - begin) * 8) + bits->bitIndex;
}

// BitBufferByteAlign
//
void BitBufferByteAlign( BitBuffer * bits, int32_t addZeros )
{
	// align bit buffer to next byte boundary, writing zeros if requested
	if ( bits->bitIndex == 0 )
		return;

	if ( addZeros )
		BitBufferWrite( bits, 0, 8 - bits->bitIndex );
	else	
		BitBufferAdvance( bits, 8 - bits->bitIndex );	
}

// BitBufferAdvance
//
void BitBufferAdvance( BitBuffer * bits, uint32_t numBits )
{
	if ( numBits )
	{
		bits->bitIndex += numBits;
		bits->cur += (bits->bitIndex >> 3);
		bits->bitIndex &= 7;
	}
}

// BitBufferRewind
//
void BitBufferRewind( BitBuffer * bits, uint32_t numBits )
{
	uint32_t	numBytes;
	
	if ( numBits == 0 )
		return;
	
	if ( bits->bitIndex >= numBits )
	{
		bits->bitIndex -= numBits;
		return;
	}
	
	numBits -= bits->bitIndex;
	bits->bitIndex = 0;

	numBytes	= numBits / 8;
	numBits		= numBits % 8;
	
	bits->cur -= numBytes;
	
	if ( numBits > 0 )
	{
		bits->bitIndex = 8 - numBits;
		bits->cur--;
	}
	
	if ( bits->cur < (bits->end - bits->byteSize) )
	{
		//DebugCMsg("BitBufferRewind: Rewound too far.");

		bits->cur		= (bits->end - bits->byteSize);
		bits->bitIndex	= 0;
	}
}

// BitBufferWrite
//
void BitBufferWrite( BitBuffer * bits, uint32_t bitValues, uint32_t numBits )
{
	uint32_t				invBitIndex;
	
	RequireAction( bits != nil, return; );
	RequireActionSilent( numBits > 0, return; );

	invBitIndex = 8 - bits->bitIndex;

	while ( numBits > 0 )
	{
		uint32_t		tmp;
		uint8_t		shift;
		uint8_t		mask;
		uint32_t		curNum;

		curNum = MIN( invBitIndex, numBits );

		tmp = bitValues >> (numBits - curNum);

		shift  = (uint8_t)(invBitIndex - curNum);
		mask   = 0xffu >> (8 - curNum);		// must be done in two steps to avoid compiler sequencing ambiguity
		mask <<= shift;

		bits->cur[0] = (bits->cur[0] & ~mask) | (((uint8_t) tmp << shift)  & mask);
		numBits -= curNum;

		// increment to next byte if need be
		invBitIndex -= curNum;
		if ( invBitIndex == 0 )
		{
			invBitIndex = 8;
			bits->cur++;
		}
	}

	bits->bitIndex = 8 - invBitIndex;
}

void	BitBufferReset( BitBuffer * bits )
//void BitBufferInit( BitBuffer * bits, uint8_t * buffer, uint32_t byteSize )
{
	bits->cur		= bits->end - bits->byteSize;
    bits->bitIndex	= 0;
}

#if PRAGMA_MARK
#pragma mark -
#endif
